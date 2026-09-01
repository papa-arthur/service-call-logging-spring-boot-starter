package com.bookit.servicecalllogging.autoconfigure;

import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import com.bookit.servicecalllogging.filter.OutboundCallExchangeFilter;
import com.bookit.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.metrics.OutboundCallMetrics;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.bookit.servicecalllogging.testsupport.StubHttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.security.CodeSource;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-023 — one property switches the entire starter off: no beans, no headers, no logs, no
 * metrics. The dependency stays on the classpath; nothing it contributes is active.
 */
@ExtendWith(OutputCaptureExtension.class)
class DisabledStarterTest {

    private StubHttpServer server;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestTemplateAutoConfiguration.class,
                    ServiceCallLoggingAutoConfiguration.class))
            .withUserConfiguration(MeterRegistryConfig.class)
            .withPropertyValues("spring.application.name=my-service",
                    "service-call-logging.enabled=false");

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
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

    @Test
    void notASingleStarterBeanIsRegistered() {
        this.runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ServiceCallLoggingAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(ServiceCallLoggingProperties.class);
            assertThat(context).doesNotHaveBean(DestinationNameResolver.class);
            assertThat(context).doesNotHaveBean(CallLogger.class);
            assertThat(context).doesNotHaveBean(ResponseCodeExtractor.class);
            assertThat(context).doesNotHaveBean(OutboundCallInterceptor.class);
            assertThat(context).doesNotHaveBean(OutboundCallExchangeFilter.class);
            assertThat(context).doesNotHaveBean(OutboundCallMetrics.class);
        });
    }

    @Test
    void noBeanDefinitionAnywhereInTheContextComesFromTheStarterArtifact() {
        this.runner.run(context -> {
            String[] starterBeans = Arrays.stream(context.getBeanDefinitionNames())
                    .filter(name -> isFromStarterArtifact(context.getType(name)))
                    .toArray(String[]::new);

            assertThat(starterBeans)
                    .as("the disabled starter must contribute nothing to the context")
                    .isEmpty();
        });
    }

    /**
     * Test fixtures live in the starter's own package namespace, so a package-name check alone
     * would flag this test's configuration classes. Code-source location separates the shipped
     * artifact ({@code target/classes}) from test fixtures ({@code target/test-classes}).
     */
    private static boolean isFromStarterArtifact(Class<?> type) {
        if (type == null || !type.getName().startsWith("com.bookit.servicecalllogging")) {
            return false;
        }
        CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return false;
        }
        String location = codeSource.getLocation().toString();
        return location.endsWith("/target/classes/") || location.endsWith("/target/classes");
    }

    @Test
    void noCorrelationHeadersAppearOnOutgoingRequests() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            context.getBean(RestTemplateBuilder.class).build()
                    .getForObject(this.server.url("/x"), String.class);

            assertThat(this.server.lastRequestHeader("X-Source-Service")).isNull();
            assertThat(this.server.lastRequestHeader("X-Destination-Service")).isNull();
        });
    }

    @Test
    void noLogEntriesAndNoMetricsAreProduced(CapturedOutput output) {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            context.getBean(RestTemplateBuilder.class).build()
                    .getForObject(this.server.url("/x"), String.class);

            assertThat(context.getBean(MeterRegistry.class)
                    .find("http.outbound.calls.total").counters()).isEmpty();
        });

        assertThat(output).doesNotContain("outbound-call");
    }

    @Test
    void theCallItselfStillWorksPerfectlyWell() {
        this.server.respondWith(200, "{\"responseCode\":0,\"data\":\"payload\"}");

        this.runner.run(context -> {
            String body = context.getBean(RestTemplateBuilder.class).build()
                    .getForObject(this.server.url("/x"), String.class);

            assertThat(body).isEqualTo("{\"responseCode\":0,\"data\":\"payload\"}");
        });
    }
}
