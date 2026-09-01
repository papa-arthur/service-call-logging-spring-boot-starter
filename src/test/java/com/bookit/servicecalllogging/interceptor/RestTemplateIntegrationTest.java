package com.bookit.servicecalllogging.interceptor;

import com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration;
import com.bookit.servicecalllogging.logging.CallLogger;
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
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack blocking-path test: a real auto-configured {@link RestTemplateBuilder}, a real
 * {@link RestTemplate}, and a real HTTP server. Asserts the wire contract and the telemetry.
 */
class RestTemplateIntegrationTest {

    private StubHttpServer server;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestTemplateAutoConfiguration.class,
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

    @Test
    void bothCorrelationHeadersReachTheDownstreamServiceOnTheWire() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            lenient(context.getBean(RestTemplateBuilder.class))
                    .getForObject(this.server.url("/charge"), String.class);

            assertThat(this.server.lastRequestHeader("X-Source-Service")).isEqualTo("my-service");
            assertThat(this.server.lastRequestHeader("X-Destination-Service"))
                    .isEqualTo("127.0.0.1:" + this.server.port());
        });
    }

    @Test
    void responseCodeZeroIsRecordedAsSuccess() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            lenient(context.getBean(RestTemplateBuilder.class))
                    .getForObject(this.server.url("/x"), String.class);

            RecordingCallLogger logger = context.getBean(RecordingCallLogger.class);
            assertThat(logger.onlyRecord().responseCode()).isEqualTo(0);
            assertThat(logger.onlyRecord().httpStatusGroup()).isEqualTo("2xx");
        });
    }

    @Test
    void responseCodeOneIsRecordedAsFailure() {
        this.server.respondWith(200, "{\"responseCode\":1}");

        this.runner.run(context -> {
            lenient(context.getBean(RestTemplateBuilder.class))
                    .getForObject(this.server.url("/x"), String.class);

            assertThat(context.getBean(RecordingCallLogger.class).onlyRecord().responseCode()).isEqualTo(1);
        });
    }

    @Test
    void emptyBodyIsRecordedAsAbsent() {
        this.server.respondWithEmptyBody(200);

        this.runner.run(context -> {
            lenient(context.getBean(RestTemplateBuilder.class))
                    .getForObject(this.server.url("/x"), String.class);

            assertThat(context.getBean(RecordingCallLogger.class).onlyRecord().responseCode()).isNull();
        });
    }

    @Test
    void statusGroupIsRecordedAccuratelyForTwoFourAndFiveHundreds() {
        this.runner.run(context -> {
            RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
            RecordingCallLogger logger = context.getBean(RecordingCallLogger.class);

            this.server.respondWith(200, "{\"responseCode\":0}");
            restTemplate.getForObject(this.server.url("/ok"), String.class);
            assertThat(logger.onlyRecord().httpStatusGroup()).isEqualTo("2xx");
            assertThat(logger.onlyRecord().httpStatusCode()).isEqualTo(200);
            logger.reset();

            this.server.respondWith(400, "{\"responseCode\":1}");
            restTemplate.getForObject(this.server.url("/bad"), String.class);
            assertThat(logger.onlyRecord().httpStatusGroup()).isEqualTo("4xx");
            assertThat(logger.onlyRecord().httpStatusCode()).isEqualTo(400);
            logger.reset();

            this.server.respondWith(500, "{\"responseCode\":1}");
            restTemplate.getForObject(this.server.url("/boom"), String.class);
            assertThat(logger.onlyRecord().httpStatusGroup()).isEqualTo("5xx");
            assertThat(logger.onlyRecord().httpStatusCode()).isEqualTo(500);
        });
    }

    @Test
    void theServiceNameHintHeaderSetByTheCallerBecomesTheDestination() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.add("service_name", "payments-service");

            restTemplate.exchange(this.server.url("/x"), org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers), String.class);

            assertThat(this.server.lastRequestHeader("X-Destination-Service")).isEqualTo("payments-service");
            assertThat(context.getBean(RecordingCallLogger.class).onlyRecord().destination())
                    .isEqualTo("payments-service");
        });
    }
}
