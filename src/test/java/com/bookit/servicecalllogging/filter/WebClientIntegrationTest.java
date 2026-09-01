package com.bookit.servicecalllogging.filter;

import com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.testsupport.RecordingCallLogger;
import com.bookit.servicecalllogging.testsupport.StubHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack reactive test: a real auto-configured {@link WebClient.Builder} with the starter's
 * customizer applied, against a real HTTP server.
 */
class WebClientIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private StubHttpServer server;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebClientAutoConfiguration.class,
                    ServiceCallLoggingAutoConfiguration.class))
            .withUserConfiguration(RecordingLoggerConfig.class)
            .withPropertyValues("spring.application.name=my-service");

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
    void bothCorrelationHeadersReachTheDownstreamServiceOnTheWire() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            WebClient webClient = context.getBean(WebClient.Builder.class).build();

            webClient.get().uri(this.server.url("/charge"))
                    .retrieve().bodyToMono(String.class).block(TIMEOUT);

            assertThat(this.server.lastRequestHeader("X-Source-Service")).isEqualTo("my-service");
            assertThat(this.server.lastRequestHeader("X-Destination-Service"))
                    .isEqualTo("127.0.0.1:" + this.server.port());
        });
    }

    @Test
    void responseCodeZeroAndOneAreRecordedWithTheirOutcomes() {
        this.runner.run(context -> {
            WebClient webClient = context.getBean(WebClient.Builder.class).build();
            RecordingCallLogger logger = context.getBean(RecordingCallLogger.class);

            this.server.respondWith(200, "{\"responseCode\":0}");
            webClient.get().uri(this.server.url("/ok"))
                    .retrieve().bodyToMono(String.class).block(TIMEOUT);
            assertThat(logger.onlyRecord().responseCode()).isEqualTo(0);
            logger.reset();

            this.server.respondWith(200, "{\"responseCode\":1}");
            webClient.get().uri(this.server.url("/nope"))
                    .retrieve().bodyToMono(String.class).block(TIMEOUT);
            assertThat(logger.onlyRecord().responseCode()).isEqualTo(1);
        });
    }

    @Test
    void statusGroupIsRecordedAccuratelyAcrossTheStatusRange() {
        this.runner.run(context -> {
            WebClient webClient = context.getBean(WebClient.Builder.class).build();
            RecordingCallLogger logger = context.getBean(RecordingCallLogger.class);

            this.server.respondWith(200, "{\"responseCode\":0}");
            readIgnoringStatus(webClient, this.server.url("/ok"));
            assertThat(logger.onlyRecord().httpStatusGroup()).isEqualTo("2xx");
            logger.reset();

            this.server.respondWith(400, "{\"responseCode\":1}");
            readIgnoringStatus(webClient, this.server.url("/bad"));
            assertThat(logger.onlyRecord().httpStatusGroup()).isEqualTo("4xx");
            logger.reset();

            this.server.respondWith(500, "{\"responseCode\":1}");
            readIgnoringStatus(webClient, this.server.url("/boom"));
            assertThat(logger.onlyRecord().httpStatusGroup()).isEqualTo("5xx");
        });
    }

    @Test
    void theBodyIsDeliveredUnchangedThroughTheAutoConfiguredClient() {
        String payload = "{\"responseCode\":0,\"items\":[1,2,3]}";
        this.server.respondWith(200, payload);

        this.runner.run(context -> {
            String body = context.getBean(WebClient.Builder.class).build()
                    .get().uri(this.server.url("/x"))
                    .retrieve().bodyToMono(String.class).block(TIMEOUT);

            assertThat(body).isEqualTo(payload);
        });
    }

    private static String readIgnoringStatus(WebClient webClient, String url) {
        return webClient.get().uri(url)
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty(""))
                .block(TIMEOUT);
    }
}
