package com.telecelghana.play.app.common.servicecalllogging.metrics;

import com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties;
import com.telecelghana.play.app.common.servicecalllogging.operation.OperationResolver;
import com.telecelghana.play.app.common.servicecalllogging.uri.DestinationUriResolver;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * Records the outbound-call counter that Grafana dashboards and alerts are built on
 * (FR-014 to FR-019).
 *
 * <p>One monotonically increasing counter, {@code <prefix>.total}, tagged from the shared tag
 * assembly in {@code tagsFor(...)} — currently {@code destination}, {@code outcome} and
 * {@code http_status_group}. Prometheus renders the
 * default as {@code http_outbound_calls_total}. Counter registration is idempotent: Micrometer
 * returns the existing counter for a name and tag-set it has already seen, so a destination's
 * series accumulates across calls rather than being recreated.
 *
 * <p>Counters alone are enough for a monitoring system to derive success and failure
 * <em>rates</em> over any window via its own rate functions (FR-017) — the starter deliberately
 * computes no rates itself.
 *
 * <p>This bean exists only when the consumer already has a {@link MeterRegistry}, so Actuator
 * is never a forced dependency (Constitution Principle II).
 */
public class OutboundCallMetrics {

    private final MeterRegistry meterRegistry;
    private final ServiceCallLoggingProperties.Metrics config;
    private final String counterName;
    private final String timerName;

    public OutboundCallMetrics(MeterRegistry meterRegistry, ServiceCallLoggingProperties properties) {
        this.meterRegistry = meterRegistry;
        this.config = properties.metrics();
        this.counterName = this.config.prefix() + ".total";
        this.timerName = this.config.prefix() + ".latency";
    }

    /**
     * Increments the counter for one completed (or failed) outbound call.
     *
     * <p>Callers wrap this, but it is defensive in its own right: a null tag value would make
     * Micrometer throw, and a metrics failure must never reach a business call.
     *
     * @param destination     the resolved destination service name
     * @param outcome         the interpreted {@code responseCode} outcome
     * @param httpStatusGroup the HTTP status classification
     */
    public void record(String destination, Outcome outcome, String httpStatusGroup) {
        // Counter only — retained for callers with no elapsed time to report.
        Counter.builder(this.counterName)
                .description("Outbound HTTP call counter, by destination, outcome and HTTP status group")
                .tags(tagsFor(destination, outcome, httpStatusGroup,
                        DestinationUriResolver.UNKNOWN, DestinationUriResolver.UNKNOWN,
                        OperationResolver.UNDEFINED))
                .register(this.meterRegistry)
                .increment();
    }

    /**
     * Increments the counter and records the call's elapsed time on the latency timer
     * (spec 003, T039).
     *
     * <p>Both meters are tagged from the same assembly, so they always carry an identical tag
     * set. Defensive throughout: a metrics failure must never reach a business call
     * (Constitution Principle I) — callers wrap this, and it avoids throwing in its own right.
     *
     * @param elapsedNanos elapsed time measured with {@code System.nanoTime()}; recorded even
     *                     when the call failed before a response arrived (FR-016)
     */
    public void record(String destination, Outcome outcome, String httpStatusGroup, long elapsedNanos) {
        record(destination, outcome, httpStatusGroup,
                DestinationUriResolver.UNKNOWN, DestinationUriResolver.UNKNOWN, elapsedNanos);
    }

    /**
     * Records one call with its URI dimensions (spec 003, T024).
     *
     * <p>The two URI values MUST be the <em>metric</em> surface from
     * {@link DestinationUriResolver.Resolved#metricValue()} — never the log surface. An
     * untemplatable URI contributes the {@code unresolved} placeholder here, because a raw path
     * carrying an identifier would be one series per request (FR-004).
     */
    public void record(String destination, Outcome outcome, String httpStatusGroup,
                       String destinationUri, String inboundUri, long elapsedNanos) {
        record(destination, outcome, httpStatusGroup, destinationUri, inboundUri,
                OperationResolver.UNDEFINED, elapsedNanos);
    }

    /**
     * Records one call with every dimension this starter publishes (spec 003, T034).
     *
     * @param operation the admitted operation name, or {@code undefined} — bounded by
     *                  {@link OperationResolver}, so this tag's value count is capped
     */
    public void record(String destination, Outcome outcome, String httpStatusGroup,
                       String destinationUri, String inboundUri, String operation,
                       long elapsedNanos) {
        Tags tags = tagsFor(destination, outcome, httpStatusGroup, destinationUri, inboundUri,
                operation);

        Counter.builder(this.counterName)
                .description("Outbound HTTP call counter, by destination, outcome and HTTP status group")
                .tags(tags)
                .register(this.meterRegistry)
                .increment();

        Timer.Builder timer = Timer.builder(this.timerName)
                .description("Outbound HTTP call latency, by destination, outcome and HTTP status group")
                .tags(tags);
        applyDistribution(timer);
        timer.register(this.meterRegistry)
                .record(Math.max(0L, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    /**
     * Chooses the timer's distribution: the consuming service's explicit boundaries when it
     * configured any usable ones, otherwise the metrics library's own default percentile
     * histogram (FR-012).
     *
     * <p>Unusable entries — null, zero or negative — are discarded rather than rejected, and if
     * nothing usable remains the delegated default applies. A bad configuration value must never
     * fail the consuming service's startup (FR-014).
     *
     * <p>Note the consequence the README must state: when nothing is configured the effective
     * boundaries belong to the library version on the classpath, not to this starter, so a
     * dependency upgrade can move them without this starter's version changing (FR-015).
     */
    private void applyDistribution(Timer.Builder timer) {
        List<Duration> configured = this.config.latencyBucketDurations();
        Duration[] usable = configured == null
                ? new Duration[0]
                : configured.stream()
                        .filter(d -> d != null && !d.isZero() && !d.isNegative())
                        .toArray(Duration[]::new);

        if (usable.length == 0) {
            timer.publishPercentileHistogram();
        } else {
            timer.serviceLevelObjectives(usable);
        }
    }

    /**
     * The one tag set every meter this class publishes is registered with (spec 003, T058).
     *
     * <p>Built once per call and shared, deliberately: a dimension added here appears on every
     * meter at the same moment, so the counter and the latency timer cannot drift apart, and
     * neither meter's registration has to know which dimensions the other one carries. That is
     * what lets the latency work land independently of the URI and operation work — whichever
     * arrives first, both meters agree.
     *
     * <p>Defensive by design: a null tag value would make Micrometer throw, and a metrics
     * failure must never reach a business call (Constitution Principle I).
     */
    private Tags tagsFor(String destination, Outcome outcome, String httpStatusGroup,
                         String destinationUri, String inboundUri, String operation) {
        return Tags.of(
                this.config.destinationTagName(), orUnknown(destination),
                this.config.outcomeTagName(), outcome == null ? Outcome.ABSENT.label() : outcome.label(),
                this.config.statusGroupTagName(), orUnknown(httpStatusGroup),
                this.config.destinationUriTagName(), orUnknown(destinationUri),
                this.config.inboundUriTagName(), orUnknown(inboundUri),
                this.config.operationTagName(),
                operation == null || operation.isBlank() ? OperationResolver.UNDEFINED : operation);
    }

    private static String orUnknown(String tagValue) {
        return tagValue == null ? "unknown" : tagValue;
    }
}
