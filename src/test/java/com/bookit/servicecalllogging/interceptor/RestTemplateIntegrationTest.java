package com.bookit.servicecalllogging.interceptor;

import com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.logging.OutboundCallRecord;
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

    // ================= User Story 1 — custom field-name pair =================

    @Test
    void aConfiguredFieldNamePairIsReadInsteadOfTheBuiltInDefaults() {
        this.server.respondWith(200, "{\"statusCode\":0,\"message\":\"OK\"}");

        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=statusCode",
                        "service-call-logging.envelopes[0].message-field=message")
                .run(context -> {
                    lenient(context.getBean(RestTemplateBuilder.class))
                            .getForObject(this.server.url("/x"), String.class);

                    OutboundCallRecord record = context.getBean(RecordingCallLogger.class).onlyRecord();
                    assertThat(record.responseCode()).isEqualTo(0);
                    assertThat(record.message()).isEqualTo("OK");
                });
    }

    @Test
    void aCallWhoseBodyDoesNotMatchTheConfiguredPairFallsBackToTheBuiltInDefaults() {
        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=statusCode",
                        "service-call-logging.envelopes[0].message-field=message")
                .run(context -> {
                    RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
                    RecordingCallLogger logger = context.getBean(RecordingCallLogger.class);

                    this.server.respondWith(200, "{\"statusCode\":0,\"message\":\"configured\"}");
                    restTemplate.getForObject(this.server.url("/configured"), String.class);
                    assertThat(logger.onlyRecord().message()).isEqualTo("configured");
                    logger.reset();

                    // a body in the original shape still works, via the built-in default entry
                    this.server.respondWith(200, "{\"responseCode\":1,\"message\":\"defaulted\"}");
                    restTemplate.getForObject(this.server.url("/default"), String.class);
                    assertThat(logger.onlyRecord().responseCode()).isEqualTo(1);
                    assertThat(logger.onlyRecord().message()).isEqualTo("defaulted");
                });
    }

    @Test
    void theBodyIsUnchangedWhenACustomCombinationIsConfigured() {
        String payload = "{\"statusCode\":0,\"message\":\"OK\",\"items\":[1,2,3]}";
        this.server.respondWith(200, payload);

        this.runner.withPropertyValues("service-call-logging.envelopes[0].code-field=statusCode")
                .run(context -> {
                    String body = lenient(context.getBean(RestTemplateBuilder.class))
                            .getForObject(this.server.url("/x"), String.class);

                    assertThat(body).isEqualTo(payload);
                });
    }

    // ================= User Story 2 — configurable successful value =================

    @Test
    void aNonDefaultSuccessfulValueDecidesSuccessAndEveryOtherCodeIsUnsuccessful() {
        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=responseCode",
                        "service-call-logging.envelopes[0].successful-value=1")
                .run(context -> {
                    RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
                    RecordingCallLogger logger = context.getBean(RecordingCallLogger.class);

                    this.server.respondWith(200, "{\"responseCode\":1}");
                    restTemplate.getForObject(this.server.url("/a"), String.class);
                    assertThat(logger.onlyRecord().responseCode()).isEqualTo(1);
                    logger.reset();

                    // 0 is no longer success under this combination
                    this.server.respondWith(200, "{\"responseCode\":0}");
                    restTemplate.getForObject(this.server.url("/b"), String.class);
                    assertThat(logger.onlyRecord().responseCode()).isZero();
                    logger.reset();

                    // a code the starter has never been told about is still logged, not dropped
                    this.server.respondWith(200, "{\"responseCode\":4711}");
                    restTemplate.getForObject(this.server.url("/c"), String.class);
                    assertThat(logger.onlyRecord().responseCode()).isEqualTo(4711);
                });
    }

    // ================= User Story 3 — several combinations at once =================

    @Test
    void eachCallIsInterpretedByTheCombinationMatchingItsOwnBody() {
        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=statusCode",
                        "service-call-logging.envelopes[0].message-field=message",
                        "service-call-logging.envelopes[1].code-field=responseCode",
                        "service-call-logging.envelopes[1].message-field=responseDescription",
                        "service-call-logging.envelopes[1].successful-value=1")
                .run(context -> {
                    RestTemplate restTemplate = lenient(context.getBean(RestTemplateBuilder.class));
                    RecordingCallLogger logger = context.getBean(RecordingCallLogger.class);

                    this.server.respondWith(200, "{\"statusCode\":0,\"message\":\"first API\"}");
                    restTemplate.getForObject(this.server.url("/api-one"), String.class);
                    assertThat(logger.onlyRecord().responseCode()).isZero();
                    assertThat(logger.onlyRecord().message()).isEqualTo("first API");
                    logger.reset();

                    this.server.respondWith(200,
                            "{\"responseCode\":1,\"responseDescription\":\"second API\"}");
                    restTemplate.getForObject(this.server.url("/api-two"), String.class);
                    assertThat(logger.onlyRecord().responseCode()).isEqualTo(1);
                    assertThat(logger.onlyRecord().message()).isEqualTo("second API");
                    logger.reset();

                    // a third shape matching neither falls back to the built-in default
                    this.server.respondWith(200, "{\"errorCode\":500,\"detail\":\"boom\"}");
                    restTemplate.getForObject(this.server.url("/api-three"), String.class);
                    assertThat(logger.onlyRecord().responseCode()).isNull();
                    assertThat(logger.onlyRecord().message()).isNull();
                });
    }

    @Test
    void anAmbiguousBodyIsResolvedByTheEarlierConfiguredCombination() {
        // both entries could match this body; contracts/envelope-matching.md says the first wins
        this.server.respondWith(200,
                "{\"statusCode\":0,\"responseCode\":1,\"message\":\"first\",\"responseDescription\":\"second\"}");

        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=statusCode",
                        "service-call-logging.envelopes[0].message-field=message",
                        "service-call-logging.envelopes[1].code-field=responseCode",
                        "service-call-logging.envelopes[1].message-field=responseDescription")
                .run(context -> {
                    lenient(context.getBean(RestTemplateBuilder.class))
                            .getForObject(this.server.url("/x"), String.class);

                    OutboundCallRecord record = context.getBean(RecordingCallLogger.class).onlyRecord();
                    assertThat(record.responseCode()).isZero();
                    assertThat(record.message()).isEqualTo("first");
                });
    }

    // ================= User Story 4 — absent, partial and mismatched configuration ==========

    @Test
    void withNoEnvelopeConfiguredBehaviourIsIdenticalToBeforeTheFeature() {
        this.server.respondWith(200, "{\"responseCode\":0,\"message\":\"OK\"}");

        this.runner.run(context -> {
            lenient(context.getBean(RestTemplateBuilder.class))
                    .getForObject(this.server.url("/x"), String.class);

            OutboundCallRecord record = context.getBean(RecordingCallLogger.class).onlyRecord();
            assertThat(record.responseCode()).isZero();
            assertThat(record.message()).isEqualTo("OK");
        });
    }

    @Test
    void aPartiallyConfiguredCombinationFallsBackPerFieldToTheDefaults() {
        this.server.respondWith(200, "{\"statusCode\":0,\"message\":\"OK\"}");

        // only the code field is configured; the message field name defaults to "message"
        this.runner.withPropertyValues("service-call-logging.envelopes[0].code-field=statusCode")
                .run(context -> {
                    lenient(context.getBean(RestTemplateBuilder.class))
                            .getForObject(this.server.url("/x"), String.class);

                    OutboundCallRecord record = context.getBean(RecordingCallLogger.class).onlyRecord();
                    assertThat(record.responseCode()).isZero();
                    assertThat(record.message()).isEqualTo("OK");
                });
    }

    @Test
    void aBodyMatchingNoCombinationAtAllIsRecordedAsAbsentAndDeliveredIntact() {
        String payload = "{\"errorCode\":500,\"detail\":\"boom\"}";
        this.server.respondWith(200, payload);

        this.runner.withPropertyValues("service-call-logging.envelopes[0].code-field=statusCode")
                .run(context -> {
                    String body = lenient(context.getBean(RestTemplateBuilder.class))
                            .getForObject(this.server.url("/x"), String.class);

                    OutboundCallRecord record = context.getBean(RecordingCallLogger.class).onlyRecord();
                    assertThat(record.responseCode()).isNull();
                    assertThat(record.message()).isNull();
                    assertThat(body).isEqualTo(payload);
                });
    }
}
