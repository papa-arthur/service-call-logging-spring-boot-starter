package com.bookit.servicecalllogging.metrics;

import com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration;
import com.bookit.servicecalllogging.testsupport.StubHttpServer;
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

    private static double count(MeterRegistry registry, String destination, String outcome, String statusGroup) {
        Counter counter = registry.find("http.outbound.calls.total")
                .tag("destination", destination)
                .tag("outcome", outcome)
                .tag("http_status_group", statusGroup)
                .counter();
        return counter == null ? 0 : counter.count();
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

    // ================= User Story 2 / 3 — configured classification reaches the counters ======

    @Test
    void aConfiguredSuccessfulValueDrivesTheSuccessAndFailureCounters() {
        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=responseCode",
                        "service-call-logging.envelopes[0].successful-value=1")
                .run(context -> {
                    RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
                    MeterRegistry registry = context.getBean(MeterRegistry.class);

                    this.server.respondWith(200, "{\"responseCode\":1}");
                    restTemplate.getForObject(this.server.url("/a"), String.class);

                    this.server.respondWith(200, "{\"responseCode\":0}");
                    restTemplate.getForObject(this.server.url("/b"), String.class);

                    String destination = "127.0.0.1:" + this.server.port();
                    assertThat(counter(registry, destination, "success", "2xx")).isEqualTo(1.0);
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

    private static double counter(MeterRegistry registry, String destination, String outcome,
                                  String statusGroup) {
        Counter counter = registry.find("http.outbound.calls.total")
                .tag("destination", destination)
                .tag("outcome", outcome)
                .tag("http_status_group", statusGroup)
                .counter();
        return counter == null ? -1 : counter.count();
    }
}
