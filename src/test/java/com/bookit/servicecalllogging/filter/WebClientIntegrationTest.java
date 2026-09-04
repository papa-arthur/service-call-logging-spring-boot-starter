package com.bookit.servicecalllogging.filter;

import com.bookit.servicecalllogging.autoconfigure.ServiceCallLoggingAutoConfiguration;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.logging.OutboundCallRecord;
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

    // ================= User Story 1 — custom field-name pair =================

    @Test
    void aConfiguredFieldNamePairIsReadInsteadOfTheBuiltInDefaults() {
        this.server.respondWith(200, "{\"statusCode\":0,\"message\":\"OK\"}");

        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=statusCode",
                        "service-call-logging.envelopes[0].message-field=message")
                .run(context -> {
                    context.getBean(WebClient.Builder.class).build()
                            .get().uri(this.server.url("/x"))
                            .retrieve().bodyToMono(String.class).block(TIMEOUT);

                    OutboundCallRecord record = context.getBean(RecordingCallLogger.class).onlyRecord();
                    assertThat(record.responseCode()).isZero();
                    assertThat(record.message()).isEqualTo("OK");
                });
    }

    @Test
    void theBodyIsUnchangedWhenACustomCombinationIsConfigured() {
        String payload = "{\"statusCode\":0,\"message\":\"OK\",\"items\":[1,2,3]}";
        this.server.respondWith(200, payload);

        this.runner.withPropertyValues("service-call-logging.envelopes[0].code-field=statusCode")
                .run(context -> {
                    String body = context.getBean(WebClient.Builder.class).build()
                            .get().uri(this.server.url("/x"))
                            .retrieve().bodyToMono(String.class).block(TIMEOUT);

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
                    WebClient webClient = context.getBean(WebClient.Builder.class).build();
                    RecordingCallLogger logger = context.getBean(RecordingCallLogger.class);

                    this.server.respondWith(200, "{\"responseCode\":1}");
                    readIgnoringStatus(webClient, this.server.url("/a"));
                    assertThat(logger.onlyRecord().responseCode()).isEqualTo(1);
                    logger.reset();

                    this.server.respondWith(200, "{\"responseCode\":4711}");
                    readIgnoringStatus(webClient, this.server.url("/b"));
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
                    WebClient webClient = context.getBean(WebClient.Builder.class).build();
                    RecordingCallLogger logger = context.getBean(RecordingCallLogger.class);

                    this.server.respondWith(200, "{\"statusCode\":0,\"message\":\"first API\"}");
                    readIgnoringStatus(webClient, this.server.url("/api-one"));
                    assertThat(logger.onlyRecord().message()).isEqualTo("first API");
                    logger.reset();

                    this.server.respondWith(200,
                            "{\"responseCode\":1,\"responseDescription\":\"second API\"}");
                    readIgnoringStatus(webClient, this.server.url("/api-two"));
                    assertThat(logger.onlyRecord().responseCode()).isEqualTo(1);
                    assertThat(logger.onlyRecord().message()).isEqualTo("second API");
                    logger.reset();

                    this.server.respondWith(200, "{\"errorCode\":500}");
                    readIgnoringStatus(webClient, this.server.url("/api-three"));
                    assertThat(logger.onlyRecord().responseCode()).isNull();
                    assertThat(logger.onlyRecord().message()).isNull();
                });
    }

    @Test
    void anAmbiguousBodyIsResolvedByTheEarlierConfiguredCombination() {
        this.server.respondWith(200,
                "{\"statusCode\":0,\"responseCode\":1,\"message\":\"first\",\"responseDescription\":\"second\"}");

        this.runner.withPropertyValues(
                        "service-call-logging.envelopes[0].code-field=statusCode",
                        "service-call-logging.envelopes[0].message-field=message",
                        "service-call-logging.envelopes[1].code-field=responseCode",
                        "service-call-logging.envelopes[1].message-field=responseDescription")
                .run(context -> {
                    readIgnoringStatus(context.getBean(WebClient.Builder.class).build(),
                            this.server.url("/x"));

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
            readIgnoringStatus(context.getBean(WebClient.Builder.class).build(), this.server.url("/x"));

            OutboundCallRecord record = context.getBean(RecordingCallLogger.class).onlyRecord();
            assertThat(record.responseCode()).isZero();
            assertThat(record.message()).isEqualTo("OK");
        });
    }

    @Test
    void aPartiallyConfiguredCombinationFallsBackPerFieldToTheDefaults() {
        this.server.respondWith(200, "{\"statusCode\":0,\"message\":\"OK\"}");

        this.runner.withPropertyValues("service-call-logging.envelopes[0].code-field=statusCode")
                .run(context -> {
                    readIgnoringStatus(context.getBean(WebClient.Builder.class).build(),
                            this.server.url("/x"));

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
                    String body = readIgnoringStatus(context.getBean(WebClient.Builder.class).build(),
                            this.server.url("/x"));

                    OutboundCallRecord record = context.getBean(RecordingCallLogger.class).onlyRecord();
                    assertThat(record.responseCode()).isNull();
                    assertThat(record.message()).isNull();
                    assertThat(body).isEqualTo(payload);
                });
    }

    // ===== spec 003 (T026) — the reactive path reads the template from a request attribute =====

    @Test
    void theWebClientUriTemplateAttributeIsReportedRatherThanTheExpandedPath() {
        // Different mechanism from the blocking path: DefaultWebClient publishes the template as
        // a request attribute, so no ThreadLocal hand-off is involved (research.md §2). Same
        // outcome, which is why each path needs its own proof.
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            context.getBean(WebClient.Builder.class).build()
                    .get().uri(this.server.url("/accounts/{id}/transfers"), 42)
                    .retrieve().bodyToMono(String.class).block(java.time.Duration.ofSeconds(20));

            RecordingCallLogger logger = (RecordingCallLogger) context.getBean(
                    com.bookit.servicecalllogging.logging.CallLogger.class);
            assertThat(logger.onlyRecord().destinationUri()).isEqualTo("/accounts/{id}/transfers");
        });
    }

    @Test
    void aPreBuiltUriOnTheReactivePathDegradesToItsRawPath() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            context.getBean(WebClient.Builder.class).build()
                    .get().uri(java.net.URI.create(this.server.url("/accounts/77/transfers")))
                    .retrieve().bodyToMono(String.class).block(java.time.Duration.ofSeconds(20));

            RecordingCallLogger logger = (RecordingCallLogger) context.getBean(
                    com.bookit.servicecalllogging.logging.CallLogger.class);
            assertThat(logger.onlyRecord().destinationUri()).isEqualTo("/accounts/77/transfers");
        });
    }

    @Test
    void theInboundUriFallsBackOnTheReactivePath() {
        // FR-008 and the starter's existing documented limitation: context propagation across the
        // reactive boundary is the consuming service's responsibility, so the fallback is expected
        // here rather than treated as a defect.
        this.server.respondWith(200, "{\"responseCode\":0}");

        this.runner.run(context -> {
            context.getBean(WebClient.Builder.class).build()
                    .get().uri(this.server.url("/x"))
                    .retrieve().bodyToMono(String.class).block(java.time.Duration.ofSeconds(20));

            RecordingCallLogger logger = (RecordingCallLogger) context.getBean(
                    com.bookit.servicecalllogging.logging.CallLogger.class);
            assertThat(logger.onlyRecord().inboundUri()).isEqualTo("unknown");
        });
    }
}
