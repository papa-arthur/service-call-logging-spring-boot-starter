package com.telecelghana.play.app.common.servicecalllogging.metrics;

import com.telecelghana.play.app.common.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.StubHttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full-stack metrics test: real HTTP calls through an auto-configured {@link RestTemplate},
 * counted into a real {@link PrometheusMeterRegistry}, then asserted against the published
 * schema in {@code contracts/metrics-schema.md}.
 */
class MetricsIntegrationTest {

    private StubHttpServer server;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestTemplateAutoConfiguration.class,
                    ServiceCallLoggingAutoConfiguration.class))
            .withUserConfiguration(PrometheusRegistryConfig.class)
            .withPropertyValues("spring.application.name=my-service");

    @Configuration(proxyBeanMethods = false)
    static class PrometheusRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        this.server = new StubHttpServer();
    }

    @AfterEach
    void tearDown() {
        this.server.close();
    }

    private static RestTemplate lenient(RestTemplateBuilder builder) {
        RestTemplate restTemplate = builder.build();
        restTemplate.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return restTemplate;
    }

    /**
     * Total count across every series matching these three tags — see the note on
     * {@link #counter(MeterRegistry, String, String, String)}. Aggregates away the URI dimensions
     * spec 003 added, so calls to different paths still sum into one total (FR-001, FR-029).
     */
    private static double count(MeterRegistry registry, String destination, String outcome, String statusGroup) {
        return registry.find("http.outbound.calls.total")
                .tag("destination", destination)
                .tag("outcome", outcome)
                .tag("http_status_group", statusGroup)
                .counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    @Test
    void countsAreAccumulatedPerDestinationAndOutcome() {
        this.runner.run(context -> {
            RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            String destination = "127.0.0.1:" + this.server.port();

            // two successes and one failure to the same destination
            this.server.respondWith(200, "{\"responseCode\":0}");
            restTemplate.getForObject(this.server.url("/a"), String.class);
            restTemplate.getForObject(this.server.url("/a"), String.class);
            this.server.respondWith(200, "{\"responseCode\":1}");
            restTemplate.getForObject(this.server.url("/a"), String.class);

            assertThat(count(registry, destination, "success", "2xx")).isEqualTo(2.0);
            assertThat(count(registry, destination, "failure", "2xx")).isEqualTo(1.0);
        });
    }

    @Test
    void distinctDestinationsProduceDistinctTimeSeries() {
        this.runner.run(context -> {
            RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            this.server.respondWith(200, "{\"responseCode\":0}");
            restTemplate.getForObject(this.server.url("/a"), String.class);

            // a second logical destination, addressed via the hint header
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.add("service_name", "dest-b");
            restTemplate.exchange(this.server.url("/b"), org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers), String.class);

            assertThat(count(registry, "127.0.0.1:" + this.server.port(), "success", "2xx")).isEqualTo(1.0);
            assertThat(count(registry, "dest-b", "success", "2xx")).isEqualTo(1.0);
        });
    }

    @Test
    void absentResponseCodeIsCountedWithTheAbsentOutcome() {
        this.runner.run(context -> {
            RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            this.server.respondWith(200, "not json at all");
            restTemplate.getForObject(this.server.url("/x"), String.class);

            assertThat(count(registry, "127.0.0.1:" + this.server.port(), "absent", "2xx")).isEqualTo(1.0);
        });
    }

    @Test
    void anUnreachableServerIsCountedAsNetworkError() {
        this.runner.run(context -> {
            RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            String unreachable = StubHttpServer.unreachableUrl();

            assertThatThrownBy(() -> restTemplate.getForObject(unreachable + "/x", String.class))
                    .isInstanceOf(ResourceAccessException.class);

            assertThat(registry.find("http.outbound.calls.total")
                    .tag("outcome", "absent")
                    .tag("http_status_group", "network-error")
                    .counter()).isNotNull();
        });
    }

    @Test
    void statusGroupsAreCountedSeparately() {
        this.runner.run(context -> {
            RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            String destination = "127.0.0.1:" + this.server.port();

            this.server.respondWith(200, "{\"responseCode\":0}");
            restTemplate.getForObject(this.server.url("/ok"), String.class);
            this.server.respondWith(404, "{\"responseCode\":1}");
            restTemplate.getForObject(this.server.url("/missing"), String.class);
            this.server.respondWith(500, "{\"responseCode\":1}");
            restTemplate.getForObject(this.server.url("/boom"), String.class);

            assertThat(count(registry, destination, "success", "2xx")).isEqualTo(1.0);
            assertThat(count(registry, destination, "failure", "4xx")).isEqualTo(1.0);
            assertThat(count(registry, destination, "failure", "5xx")).isEqualTo(1.0);
        });
    }

    @Test
    void thePrometheusScrapeOutputMatchesThePublishedSchema() {
        this.runner.run(context -> {
            RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);

            this.server.respondWith(200, "{\"responseCode\":0}");
            restTemplate.getForObject(this.server.url("/x"), String.class);

            String scrape = registry.scrape();

            assertThat(scrape).contains("# TYPE http_outbound_calls_total counter");
            assertThat(scrape).contains("http_outbound_calls_total{");
            assertThat(scrape).contains("destination=\"127.0.0.1:" + this.server.port() + "\"");
            assertThat(scrape).contains("outcome=\"success\"");
            assertThat(scrape).contains("http_status_group=\"2xx\"");
        });
    }

    @Test
    void aCustomPrefixChangesThePrometheusMetricName() {
        this.runner.withPropertyValues("service-call-logging.metrics.prefix=custom.outbound")
                .run(context -> {
                    RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
                    PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);

                    this.server.respondWith(200, "{\"responseCode\":0}");
                    restTemplate.getForObject(this.server.url("/x"), String.class);

                    assertThat(registry.scrape()).contains("custom_outbound_total");
                    assertThat(registry.scrape()).doesNotContain("http_outbound_calls_total");
                });
    }

    @Test
    void metricsAreRecordedOnTheReactivePathToo() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.web.reactive.function.client
                                .WebClientAutoConfiguration.class,
                        ServiceCallLoggingAutoConfiguration.class))
                .withUserConfiguration(PrometheusRegistryConfig.class)
                .withPropertyValues("spring.application.name=my-service")
                .run(context -> {
                    this.server.respondWith(200, "{\"responseCode\":0}");

                    context.getBean(org.springframework.web.reactive.function.client.WebClient.Builder.class)
                            .build().get().uri(this.server.url("/x"))
                            .retrieve().bodyToMono(String.class).block(java.time.Duration.ofSeconds(20));

                    assertThat(count(context.getBean(MeterRegistry.class),
                            "127.0.0.1:" + this.server.port(), "success", "2xx")).isEqualTo(1.0);
                });
    }

    @Test
    void theReactivePathHonoursTheConfiguredSuccessfulValueToo() {
        // Reactive parity for FR-014. The blocking equivalent is
        // aConfiguredSuccessfulValueDrivesTheSuccessAndFailureCounters; without this test the
        // reactive path could classify against a hard-coded 0 and every existing assertion
        // (which only checks raw responseCode values) would still pass.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.web.reactive.function.client
                                .WebClientAutoConfiguration.class,
                        ServiceCallLoggingAutoConfiguration.class))
                .withUserConfiguration(PrometheusRegistryConfig.class)
                .withPropertyValues("spring.application.name=my-service",
                        "service-call-logging.envelopes[0].code-field=responseCode",
                        "service-call-logging.envelopes[0].successful-value=1")
                .run(context -> {
                    org.springframework.web.reactive.function.client.WebClient webClient =
                            context.getBean(org.springframework.web.reactive.function.client
                                    .WebClient.Builder.class).build();
                    String destination = "127.0.0.1:" + this.server.port();

                    // Deliberately ASYMMETRIC: two calls at the configured success value, one
                    // below it. Equal counts would still match if the classification were merely
                    // inverted, so the asymmetry is what gives this assertion its teeth.
                    this.server.respondWith(200, "{\"responseCode\":1}");
                    for (String path : new String[]{"/a", "/b"}) {
                        webClient.get().uri(this.server.url(path)).retrieve()
                                .bodyToMono(String.class).block(java.time.Duration.ofSeconds(20));
                    }

                    // 0 is no longer success under this combination
                    this.server.respondWith(200, "{\"responseCode\":0}");
                    webClient.get().uri(this.server.url("/c")).retrieve()
                            .bodyToMono(String.class).block(java.time.Duration.ofSeconds(20));

                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    assertThat(count(registry, destination, "success", "2xx")).isEqualTo(2.0);
                    assertThat(count(registry, destination, "failure", "2xx")).isEqualTo(1.0);
                });
    }

    // ================= User Story 2 / 3 — configured classification reaches the counters ======

    @Test
    void aConfiguredSuccessfulValueDrivesTheSuccessAndFailureCounters() {
        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=responseCode",
                        "service-call-logging.envelopes[0].successful-value=1")
                .run(context -> {
                    RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
                    MeterRegistry registry = context.getBean(MeterRegistry.class);

                    // Asymmetric for the same reason as the reactive test below: equal counts
                    // cannot distinguish correct classification from inverted classification.
                    this.server.respondWith(200, "{\"responseCode\":1}");
                    restTemplate.getForObject(this.server.url("/a"), String.class);
                    restTemplate.getForObject(this.server.url("/b"), String.class);

                    this.server.respondWith(200, "{\"responseCode\":0}");
                    restTemplate.getForObject(this.server.url("/c"), String.class);

                    String destination = "127.0.0.1:" + this.server.port();
                    assertThat(counter(registry, destination, "success", "2xx")).isEqualTo(2.0);
                    assertThat(counter(registry, destination, "failure", "2xx")).isEqualTo(1.0);
                });
    }

    @Test
    void threeSimultaneousCombinationsAreCountedIndependentlyAndCorrectly() {
        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=statusCode",
                        "service-call-logging.envelopes[0].message-field=message",
                        "service-call-logging.envelopes[1].code-field=responseCode",
                        "service-call-logging.envelopes[1].message-field=responseDescription",
                        "service-call-logging.envelopes[1].successful-value=1")
                .run(context -> {
                    RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    String destination = "127.0.0.1:" + this.server.port();

                    // combination 1: statusCode 0 means success
                    this.server.respondWith(200, "{\"statusCode\":0,\"message\":\"ok\"}");
                    restTemplate.getForObject(this.server.url("/one"), String.class);

                    // combination 2: responseCode 1 means success
                    this.server.respondWith(200, "{\"responseCode\":1,\"responseDescription\":\"ok\"}");
                    restTemplate.getForObject(this.server.url("/two"), String.class);

                    // matches neither: absent
                    this.server.respondWith(200, "{\"errorCode\":9}");
                    restTemplate.getForObject(this.server.url("/three"), String.class);

                    assertThat(counter(registry, destination, "success", "2xx")).isEqualTo(2.0);
                    assertThat(counter(registry, destination, "absent", "2xx")).isEqualTo(1.0);
                });
    }

    /**
     * Total count across every series matching these three tags.
     *
     * <p>Sums rather than taking a single counter, because spec 003 added the destination-URI and
     * inbound-URI dimensions: two calls to the same destination on different paths are now
     * legitimately separate series, which is the entire point of the URI dimension (FR-001). This
     * helper asserts a total, so it must aggregate away the dimensions it does not name — the same
     * thing a consumer's dashboard query does, and the reason FR-029 says such queries keep
     * working. Written to stay correct when User Story 2 adds the operation dimension too.
     */
    private static double counter(MeterRegistry registry, String destination, String outcome,
                                  String statusGroup) {
        java.util.Collection<Counter> matching = registry.find("http.outbound.calls.total")
                .tag("destination", destination)
                .tag("outcome", outcome)
                .tag("http_status_group", statusGroup)
                .counters();
        return matching.isEmpty() ? -1 : matching.stream().mapToDouble(Counter::count).sum();
    }

    // ===== spec 003 (T051) — end-state tag-set check across both meters =====

    @Test
    void bothMetersCarryIdenticalTagSetsAndExactlyTheDeclaredKeys() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            org.springframework.web.client.RestTemplate restTemplate =
                    context.getBean(org.springframework.boot.web.client.RestTemplateBuilder.class).build();
            restTemplate.getForObject(this.server.url("/accounts/{id}"), String.class, 7);

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            java.util.Set<String> counterKeys = registry.find("http.outbound.calls.total")
                    .counters().stream().findFirst().orElseThrow()
                    .getId().getTags().stream()
                    .map(io.micrometer.core.instrument.Tag::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> timerKeys = registry.find("http.outbound.calls.latency")
                    .timers().stream().findFirst().orElseThrow()
                    .getId().getTags().stream()
                    .map(io.micrometer.core.instrument.Tag::getKey)
                    .collect(java.util.stream.Collectors.toSet());

            assertThat(counterKeys).containsExactlyInAnyOrder(
                    "destination", "outcome", "http_status_group",
                    "destination_uri", "inbound_uri", "operation");
            assertThat(timerKeys)
                    .as("identical by construction: both meters are tagged from one shared assembly")
                    .isEqualTo(counterKeys);
        });
    }

    @Test
    void theOutcomeTagsThreeValuesAllSurviveTheTagAdditionsOnBothMeters() {
        // FR-027 end-state re-check. The red test for this lives in LatencyDistributionTest
        // (T036), ahead of the timer's implementation; this confirms the spec-003 tag additions
        // did not perturb it once everything is wired together.
        this.runner.run(context -> {
            org.springframework.web.client.RestTemplate restTemplate =
                    context.getBean(org.springframework.boot.web.client.RestTemplateBuilder.class).build();

            this.server.respondWith(200, "{\"responseCode\":0}");
            restTemplate.getForObject(this.server.url("/ok"), String.class);
            this.server.respondWith(200, "{\"responseCode\":9}");
            restTemplate.getForObject(this.server.url("/bad"), String.class);
            this.server.respondWith(200, "not json at all");
            restTemplate.getForObject(this.server.url("/absent"), String.class);

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            for (String meter : new String[]{"http.outbound.calls.total", "http.outbound.calls.latency"}) {
                java.util.Set<String> outcomes = registry.find(meter).meters().stream()
                        .flatMap(m -> m.getId().getTags().stream())
                        .filter(t -> t.getKey().equals("outcome"))
                        .map(io.micrometer.core.instrument.Tag::getValue)
                        .collect(java.util.stream.Collectors.toSet());

                assertThat(outcomes)
                        .as("%s must keep all three outcome values distinct, none collapsed", meter)
                        .containsExactlyInAnyOrder("success", "failure", "absent");
            }
        });
    }
}
