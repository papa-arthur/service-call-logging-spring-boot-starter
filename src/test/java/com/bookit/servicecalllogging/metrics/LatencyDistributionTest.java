package com.bookit.servicecalllogging.metrics;

import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import com.bookit.servicecalllogging.testsupport.TestProperties;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 003, User Story 3 — the latency distribution (T036, T037).
 *
 * <p>A Prometheus registry is used deliberately: {@code publishPercentileHistogram()} is only
 * honoured by registries that declare histogram support, so a {@code SimpleMeterRegistry} would
 * report no buckets at all and the delegated-default assertions would prove nothing.
 */
class LatencyDistributionTest {

    private static final String TIMER = "http.outbound.calls.latency";

    private PrometheusMeterRegistry registry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    private OutboundCallMetrics metricsWith(PrometheusMeterRegistry registry, String... buckets) {
        ServiceCallLoggingProperties properties = new ServiceCallLoggingProperties(
                true, "X-Source-Service", "X-Destination-Service", "service_name", 1_048_576,
                buckets.length == 0
                        ? TestProperties.defaultMetrics()
                        : TestProperties.metricsWithLatencyBuckets(buckets),
                List.of());
        return new OutboundCallMetrics(registry, properties);
    }

    private double[] boundariesOf(PrometheusMeterRegistry registry) {
        Timer timer = registry.find(TIMER).timer();
        assertThat(timer).as("the latency timer must be registered").isNotNull();
        // bucket() is in the timer's base unit (nanoseconds); assert in milliseconds.
        return Arrays.stream(timer.takeSnapshot().histogramCounts())
                .mapToDouble(c -> c.bucket(java.util.concurrent.TimeUnit.MILLISECONDS))
                .filter(Double::isFinite)
                .toArray();
    }

    // ===== T036: default and configured boundaries =====

    @Test
    void unconfiguredDelegatesToTheMetricsLibrarysOwnDefaultDistribution() {
        PrometheusMeterRegistry registry = registry();

        metricsWith(registry).record("svc:8080", Outcome.SUCCESS, "2xx", Duration.ofMillis(120).toNanos());

        double[] boundaries = boundariesOf(registry);
        assertThat(boundaries)
                .as("the library's default percentile histogram publishes many boundaries, "
                        + "not a short starter-defined ladder (FR-012)")
                .hasSizeGreaterThan(20);
    }

    @Test
    void percentileAndShareWithinBoundaryViewsAreDerivable() {
        PrometheusMeterRegistry registry = registry();
        OutboundCallMetrics metrics = metricsWith(registry);

        metrics.record("svc:8080", Outcome.SUCCESS, "2xx", Duration.ofMillis(10).toNanos());
        metrics.record("svc:8080", Outcome.SUCCESS, "2xx", Duration.ofMillis(500).toNanos());
        metrics.record("svc:8080", Outcome.SUCCESS, "2xx", Duration.ofSeconds(3).toNanos());

        Timer timer = registry.find(TIMER).timer();
        assertThat(timer.count()).isEqualTo(3);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThan(3_000.0);
        // cumulative counts strictly increase across boundaries — the shape a share-within-boundary
        // or histogram_quantile query needs (FR-011)
        CountAtBucket[] counts = timer.takeSnapshot().histogramCounts();
        assertThat(counts[counts.length - 1].count()).isEqualTo(3.0);
    }

    @Test
    void configuredBoundariesReplaceTheDelegatedDefault() {
        PrometheusMeterRegistry registry = registry();

        metricsWith(registry, "50ms", "200ms", "1s")
                .record("svc:8080", Outcome.SUCCESS, "2xx", Duration.ofMillis(120).toNanos());

        assertThat(boundariesOf(registry))
                .as("exactly the three configured boundaries, in milliseconds (FR-013)")
                .containsExactly(50.0, 200.0, 1000.0);
    }

    // ===== T036 (C1 remediation): the outcome tag's three values on the new meter =====

    @Test
    void theTimerPublishesAllThreeOutcomeValuesAsDistinctSeries() {
        PrometheusMeterRegistry registry = registry();
        OutboundCallMetrics metrics = metricsWith(registry);

        metrics.record("svc:8080", Outcome.SUCCESS, "2xx", 1_000_000L);
        metrics.record("svc:8080", Outcome.FAILURE, "2xx", 1_000_000L);
        metrics.record("svc:8080", Outcome.ABSENT, "2xx", 1_000_000L);

        assertThat(registry.find(TIMER).timers())
                .as("three outcome values must yield three distinct series, none collapsed (FR-027)")
                .hasSize(3);
        assertThat(registry.find(TIMER).tag("outcome", "success").timer()).isNotNull();
        assertThat(registry.find(TIMER).tag("outcome", "failure").timer()).isNotNull();
        assertThat(registry.find(TIMER).tag("outcome", "absent").timer())
                .as("a call whose response code could not be determined is neither a success "
                        + "nor a failure and must stay distinguishable from both (FR-027)")
                .isNotNull();
    }

    @Test
    void aNullOutcomeIsRecordedAsAbsentRatherThanThrowing() {
        PrometheusMeterRegistry registry = registry();

        metricsWith(registry).record("svc:8080", null, "2xx", 1_000_000L);

        assertThat(registry.find(TIMER).tag("outcome", "absent").timer()).isNotNull();
    }

    @Test
    void theTimerCarriesATagSetIdenticalToTheCounters() {
        PrometheusMeterRegistry registry = registry();

        metricsWith(registry).record("svc:8080", Outcome.SUCCESS, "2xx", 1_000_000L);

        assertThat(registry.find(TIMER).timer().getId().getTags())
                .isEqualTo(registry.find("http.outbound.calls.total").counter().getId().getTags());
    }

    // ===== T037: degradation — empty / unusable boundaries, startup must survive =====

    @Test
    void anEmptyBucketListFallsBackToTheDelegatedDefault() {
        PrometheusMeterRegistry registry = registry();

        metricsWith(registry).record("svc:8080", Outcome.SUCCESS, "2xx", 1_000_000L);

        assertThat(boundariesOf(registry)).hasSizeGreaterThan(20);
    }

    @Test
    void zeroAndNegativeBoundariesAreDiscardedRatherThanFailing() {
        PrometheusMeterRegistry registry = registry();

        metricsWith(registry, "0s", "-5ms")
                .record("svc:8080", Outcome.SUCCESS, "2xx", 1_000_000L);

        assertThat(boundariesOf(registry))
                .as("no usable boundary remains, so the delegated default applies (FR-014)")
                .hasSizeGreaterThan(20);
    }

    @Test
    void usableBoundariesSurviveAlongsideUnusableOnes() {
        PrometheusMeterRegistry registry = registry();

        metricsWith(registry, "0s", "250ms", "-1ms", "not-a-duration")
                .record("svc:8080", Outcome.SUCCESS, "2xx", 1_000_000L);

        assertThat(boundariesOf(registry))
                .as("only the one usable boundary survives; the rest are discarded (FR-014)")
                .containsExactly(250.0);
    }

    @Test
    void theApplicationContextStillStartsWithAnUnparseableBucketList() {
        // FR-014 — a bad value must not fail the consuming service's startup.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration.class))
                .withPropertyValues("service-call-logging.metrics.latency-buckets=not-a-duration")
                .run(context -> assertThat(context)
                        .as("startup must survive an unusable bucket value")
                        .hasNotFailed());
    }

    @Test
    void theApplicationContextStillStartsWithAnEmptyBucketList() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration.class))
                .withPropertyValues("service-call-logging.metrics.latency-buckets=")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
