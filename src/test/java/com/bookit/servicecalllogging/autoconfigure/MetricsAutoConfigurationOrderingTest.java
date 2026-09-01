package com.bookit.servicecalllogging.autoconfigure;

import com.bookit.servicecalllogging.metrics.OutboundCallMetrics;
import com.bookit.servicecalllogging.testsupport.StubHttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a real ordering defect.
 *
 * <p>{@code @ConditionalOnBean(MeterRegistry.class)} only sees bean definitions registered
 * <em>before</em> the declaring auto-configuration is evaluated. Supplying a {@code MeterRegistry}
 * through {@code withUserConfiguration(...)} registers it first and therefore always satisfies
 * the condition — which hides the bug. In a real application the registry arrives from
 * Actuator's own auto-configuration, and without an explicit ordering declaration this starter
 * was evaluated first and the metrics bean was silently never created: calls were logged but no
 * counter ever appeared at {@code /actuator/prometheus}.
 *
 * <p>These tests therefore obtain the registry the way a real consumer does — from the Actuator
 * auto-configurations — so the ordering guarantee is genuinely exercised.
 */
class MetricsAutoConfigurationOrderingTest {

    private StubHttpServer server;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    // exactly the ordering a real application produces
                    MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class,
                    SimpleMetricsExportAutoConfiguration.class,
                    RestTemplateAutoConfiguration.class,
                    ServiceCallLoggingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=my-service");

    @BeforeEach
    void setUp() throws IOException {
        this.server = new StubHttpServer();
    }

    @AfterEach
    void tearDown() {
        this.server.close();
    }

    @Test
    void theMetricsBeanIsCreatedWhenTheRegistryComesFromActuatorAutoConfiguration() {
        this.runner.run(context -> {
            assertThat(context).hasSingleBean(MeterRegistry.class);
            assertThat(context).hasSingleBean(OutboundCallMetrics.class);
        });
    }

    @Test
    void aRealCallActuallyIncrementsTheCounter() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            context.getBean(RestTemplateBuilder.class).build()
                    .getForObject(this.server.url("/x"), String.class);

            assertThat(context.getBean(MeterRegistry.class)
                    .find("http.outbound.calls.total")
                    .tag("destination", "127.0.0.1:" + this.server.port())
                    .tag("outcome", "success")
                    .tag("http_status_group", "2xx")
                    .counter())
                    .as("the counter must exist after a real instrumented call")
                    .isNotNull();
        });
    }

    @Test
    void everyOutcomeReachesTheRegistryThroughTheRealWiring() {
        this.runner.run(context -> {
            RestTemplateBuilder builder = context.getBean(RestTemplateBuilder.class);
            MeterRegistry registry = context.getBean(MeterRegistry.class);

            this.server.respondWith(200, "{\"responseCode\":0}");
            builder.build().getForObject(this.server.url("/a"), String.class);
            this.server.respondWith(200, "{\"responseCode\":1}");
            builder.build().getForObject(this.server.url("/b"), String.class);
            this.server.respondWith(200, "plain text");
            builder.build().getForObject(this.server.url("/c"), String.class);

            assertThat(registry.find("http.outbound.calls.total").counters())
                    .as("success, failure and absent must each have their own series")
                    .hasSize(3);
        });
    }
}
