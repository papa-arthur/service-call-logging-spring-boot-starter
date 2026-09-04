package com.telecelghana.play.app.common.servicecalllogging.autoconfigure;

import com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.RecordingCallLogger;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.StubHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-003, FR-004 — both correlation header names are configurable, and the configured names are
 * what actually appear on the wire on both client paths.
 */
class CustomHeaderNamesTest {

    private StubHttpServer server;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestTemplateAutoConfiguration.class,
                    WebClientAutoConfiguration.class,
                    ServiceCallLoggingAutoConfiguration.class))
            .withUserConfiguration(RecordingLoggerConfig.class)
            .withPropertyValues(
                    "spring.application.name=my-service",
                    "service-call-logging.source-header-name=X-From-Service",
                    "service-call-logging.destination-header-name=X-To-Service");

    @Configuration(proxyBeanMethods = false)
    static class RecordingLoggerConfig {
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
    void theBlockingPathUsesTheConfiguredHeaderNames() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            context.getBean(RestTemplateBuilder.class).build()
                    .getForObject(this.server.url("/x"), String.class);

            assertThat(this.server.lastRequestHeader("X-From-Service")).isEqualTo("my-service");
            assertThat(this.server.lastRequestHeader("X-To-Service"))
                    .isEqualTo("127.0.0.1:" + this.server.port());

            // the defaults must be entirely absent
            assertThat(this.server.lastRequestHeader("X-Source-Service")).isNull();
            assertThat(this.server.lastRequestHeader("X-Destination-Service")).isNull();
        });
    }

    @Test
    void theReactivePathUsesTheConfiguredHeaderNames() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            context.getBean(WebClient.Builder.class).build()
                    .get().uri(this.server.url("/x"))
                    .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(20));

            assertThat(this.server.lastRequestHeader("X-From-Service")).isEqualTo("my-service");
            assertThat(this.server.lastRequestHeader("X-To-Service"))
                    .isEqualTo("127.0.0.1:" + this.server.port());
            assertThat(this.server.lastRequestHeader("X-Source-Service")).isNull();
            assertThat(this.server.lastRequestHeader("X-Destination-Service")).isNull();
        });
    }

    @Test
    void aCustomHintHeaderNameIsHonoured() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.withPropertyValues("service-call-logging.service-name-hint-header=X-Target-Service")
                .run(context -> {
                    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                    headers.add("X-Target-Service", "billing-service");

                    context.getBean(RestTemplateBuilder.class).build().exchange(
                            this.server.url("/x"), org.springframework.http.HttpMethod.GET,
                            new org.springframework.http.HttpEntity<>(headers), String.class);

                    assertThat(this.server.lastRequestHeader("X-To-Service")).isEqualTo("billing-service");
                    assertThat(context.getBean(RecordingCallLogger.class).onlyRecord().destination())
                            .isEqualTo("billing-service");
                });
    }

    @Test
    void telemetryStillCarriesTheSameValuesRegardlessOfHeaderNaming() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            context.getBean(RestTemplateBuilder.class).build()
                    .getForObject(this.server.url("/x"), String.class);

            assertThat(context.getBean(RecordingCallLogger.class).onlyRecord().source())
                    .isEqualTo("my-service");
        });
    }
}
