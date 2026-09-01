package com.bookit.servicecalllogging.autoconfigure;

import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.bookit.servicecalllogging.testsupport.RecordingCallLogger;
import com.bookit.servicecalllogging.testsupport.StubHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-021 — every starter bean is replaceable. Overriding the resolver is the documented way to
 * normalise high-cardinality destination names before they become metric labels.
 */
class DestinationResolverOverrideTest {

    private StubHttpServer server;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestTemplateAutoConfiguration.class,
                    ServiceCallLoggingAutoConfiguration.class))
            .withPropertyValues("spring.application.name=my-service");

    /** Collapses every loopback address to one stable label. */
    static class NormalisingResolver extends DestinationNameResolver {
        NormalisingResolver() {
            super("overridden-source");
        }

        @Override
        public String resolve(URI uri, String hintHeaderValue) {
            return "normalised-destination";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OverrideConfig {
        @Bean
        DestinationNameResolver destinationNameResolver() {
            return new NormalisingResolver();
        }

        @Bean
        CallLogger callLogger() {
            return new RecordingCallLogger();
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
    void aConsumerResolverReplacesTheStarterDefault() {
        this.runner.withUserConfiguration(OverrideConfig.class).run(context -> {
            assertThat(context).hasSingleBean(DestinationNameResolver.class);
            assertThat(context.getBean(DestinationNameResolver.class))
                    .isInstanceOf(NormalisingResolver.class);
            assertThat(context).doesNotHaveBean("jacksonDestinationNameResolver");
        });
    }

    @Test
    void theOverriddenResolverDrivesBothTheHeaderAndTheTelemetry() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.withUserConfiguration(OverrideConfig.class).run(context -> {
            context.getBean(RestTemplateBuilder.class).build()
                    .getForObject(this.server.url("/x"), String.class);

            assertThat(this.server.lastRequestHeader("X-Destination-Service"))
                    .isEqualTo("normalised-destination");
            assertThat(this.server.lastRequestHeader("X-Source-Service"))
                    .isEqualTo("overridden-source");
            assertThat(context.getBean(RecordingCallLogger.class).onlyRecord().destination())
                    .isEqualTo("normalised-destination");
        });
    }

    @Test
    void withoutAnOverrideTheStarterResolverIsUsed() {
        this.runner.run(context ->
                assertThat(context.getBean(DestinationNameResolver.class))
                        .isNotInstanceOf(NormalisingResolver.class));
    }
}
