package com.bookit.servicecalllogging.interceptor;

import com.bookit.servicecalllogging.EnvelopeFieldExtractor;
import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import com.bookit.servicecalllogging.extractor.JacksonEnvelopeFieldExtractor;
import com.bookit.servicecalllogging.extractor.JacksonResponseCodeExtractor;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.logging.OutboundCallRecord;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.bookit.servicecalllogging.testsupport.RecordingCallLogger;
import com.bookit.servicecalllogging.testsupport.StubHttpServer;
import com.bookit.servicecalllogging.testsupport.TestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constitution Principle I — the Non-Intrusion merge gate for the blocking path.
 *
 * <p>Every test here asserts the same invariant from a different angle: instrumentation may
 * lose telemetry, but it may never change what the business caller observes.
 */
@Tag("non-intrusion")
class NonIntrusionRestTemplateTest {

    private StubHttpServer server;
    private RecordingCallLogger callLogger;

    @BeforeEach
    void setUp() throws IOException {
        this.server = new StubHttpServer();
        this.callLogger = new RecordingCallLogger();
    }

    @AfterEach
    void tearDown() {
        this.server.close();
    }

    private RestTemplate instrumented(ResponseCodeExtractor extractor, CallLogger logger, int cap) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"), extractor, logger,
                TestProperties.withMaxBodyBytes(cap)));
        return restTemplate;
    }

    private RestTemplate instrumented() {
        return instrumented(new JacksonResponseCodeExtractor(), this.callLogger, 1_048_576);
    }

    // ---- 1. body byte-for-byte identical -----------------------------------------------

    @Test
    void instrumentedResponseIsByteForByteIdenticalToAnUninstrumentedOne() {
        String payload = "{\"responseCode\":0,\"data\":{\"id\":42,\"name\":\"widget\"}}";
        this.server.respondWith(200, payload);

        byte[] uninstrumented = new RestTemplate().getForObject(this.server.url("/x"), byte[].class);
        byte[] viaStarter = instrumented().getForObject(this.server.url("/x"), byte[].class);

        assertThat(viaStarter).isEqualTo(uninstrumented);
        assertThat(new String(viaStarter, StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    // ---- 2. extractor throws ----------------------------------------------------------

    @Test
    void anExtractorThatThrowsLosesTelemetryButNotTheCall() {
        String payload = "{\"responseCode\":0}";
        this.server.respondWith(200, payload);
        ResponseCodeExtractor exploding = bytes -> {
            throw new IllegalStateException("extractor exploded");
        };

        RestTemplate restTemplate = instrumented(exploding, this.callLogger, 1_048_576);

        String body = restTemplate.getForObject(this.server.url("/x"), String.class);

        assertThat(body).isEqualTo(payload);
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }

    // ---- 3. logger throws ------------------------------------------------------------

    @Test
    void aLoggerThatThrowsLosesTelemetryButNotTheCall() {
        String payload = "{\"responseCode\":0}";
        this.server.respondWith(200, payload);
        CallLogger exploding = new CallLogger() {
            @Override
            public void log(OutboundCallRecord record) {
                throw new IllegalStateException("logger exploded");
            }
        };

        RestTemplate restTemplate = instrumented(new JacksonResponseCodeExtractor(), exploding, 1_048_576);

        assertThatCode(() -> {
            String body = restTemplate.getForObject(this.server.url("/x"), String.class);
            assertThat(body).isEqualTo(payload);
        }).doesNotThrowAnyException();
    }

    // ---- 4. empty body ---------------------------------------------------------------

    @Test
    void anEmptyBodyDegradesToAbsentAndTheCallSucceeds() {
        this.server.respondWithEmptyBody(200);

        String body = instrumented().getForObject(this.server.url("/x"), String.class);

        assertThat(body).isNull();
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
        assertThat(this.callLogger.onlyRecord().httpStatusGroup()).isEqualTo("2xx");
    }

    // ---- 5. non-JSON body ------------------------------------------------------------

    @Test
    void aNonJsonBodyDegradesToAbsentAndIsReturnedIntact() {
        String payload = "<html><body>totally not json</body></html>";
        this.server.contentType("text/html").respondWith(200, payload);

        String body = instrumented().getForObject(this.server.url("/x"), String.class);

        assertThat(body).isEqualTo(payload);
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }

    // ---- 6. body larger than the cap -------------------------------------------------

    @Test
    void anOversizedBodyDegradesToAbsentAndEveryByteStillReachesTheCaller() {
        byte[] payload = new byte[1_400_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('a' + (i % 26));
        }
        this.server.respondWith(200, payload);

        byte[] body = instrumented().getForObject(this.server.url("/x"), byte[].class);

        assertThat(body).hasSize(payload.length);
        assertThat(body).isEqualTo(payload);
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }

    // ---- extra: an extractor that violates the contract by returning null ------------

    @Test
    void anExtractorReturningNullIsToleratedWithoutBreakingTheCall() {
        String payload = "{\"responseCode\":0}";
        this.server.respondWith(200, payload);
        ResponseCodeExtractor returnsNull = bytes -> null;

        String body = instrumented(returnsNull, this.callLogger, 1_048_576)
                .getForObject(this.server.url("/x"), String.class);

        assertThat(body).isEqualTo(payload);
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }

    // ---- extra: an error status is still delivered unchanged --------------------------

    @Test
    void serverErrorBodiesAreDeliveredToTheCallerUnchanged() {
        this.server.respondWith(500, "{\"responseCode\":1,\"error\":\"boom\"}");
        RestTemplate restTemplate = instrumented();
        restTemplate.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });

        String body = restTemplate.getForObject(this.server.url("/x"), String.class);

        assertThat(body).isEqualTo("{\"responseCode\":1,\"error\":\"boom\"}");
        assertThat(this.callLogger.onlyRecord().httpStatusGroup()).isEqualTo("5xx");
        assertThat(this.callLogger.onlyRecord().responseCode()).isEqualTo(1);
    }

    @Test
    void extractorContractIsHonouredByTheShippedDefault() {
        assertThat(new JacksonResponseCodeExtractor().extract("garbage".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(Optional.empty());
    }

    // ---- 7. the envelope matching engine fails -----------------------------------------

    @Test
    void anExplodingEnvelopeExtractorLosesOnlyTheMessageAndNeverTheCall() {
        String payload = "{\"responseCode\":0,\"message\":\"OK\"}";
        this.server.respondWith(200, payload);
        EnvelopeFieldExtractor exploding = bytes -> {
            throw new IllegalStateException("envelope extractor exploded");
        };

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"), new JacksonResponseCodeExtractor(),
                this.callLogger, null, TestProperties.defaults(), exploding));

        String body = restTemplate.getForObject(this.server.url("/x"), String.class);

        assertThat(body).isEqualTo(payload);
        OutboundCallRecord record = this.callLogger.onlyRecord();
        assertThat(record.responseCode()).isEqualTo(0);
        assertThat(record.message()).isNull();
    }

    @Test
    void aMalformedEnvelopeConfigurationStillInstrumentsAndNeverBreaksTheCall() {
        String payload = "{\"responseCode\":0,\"message\":\"OK\"}";
        this.server.respondWith(200, payload);

        // a null entry and a blank-field entry are both tolerated
        List<ServiceCallLoggingProperties.Envelope> malformed = Arrays.asList(
                null, new ServiceCallLoggingProperties.Envelope("   ", "  ", 0));
        EnvelopeFieldExtractor extractor = new JacksonEnvelopeFieldExtractor(malformed);

        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"), new JacksonResponseCodeExtractor(),
                this.callLogger, null, TestProperties.defaults(), extractor));

        assertThatCode(() -> {
            String body = restTemplate.getForObject(this.server.url("/x"), String.class);
            assertThat(body).isEqualTo(payload);
        }).doesNotThrowAnyException();

        assertThat(this.callLogger.onlyRecord().responseCode()).isEqualTo(0);
    }

    // ---- spec 003 (T049): every new step made to throw in turn ------------------------

    /**
     * Builds a RestTemplate whose spec-003 collaborators are injected, so each new step can be
     * made to explode independently. Any one of them failing must leave the response
     * byte-for-byte identical and the call's outcome untouched (FR-037, SC-005).
     */
    private RestTemplate withCollaborators(
            com.bookit.servicecalllogging.uri.DestinationUriResolver destinationUriResolver,
            com.bookit.servicecalllogging.uri.InboundUriResolver inboundUriResolver,
            com.bookit.servicecalllogging.operation.OperationResolver operationResolver,
            com.bookit.servicecalllogging.metrics.OutboundCallMetrics metrics,
            CallLogger logger) {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"), new JacksonResponseCodeExtractor(),
                logger, metrics, TestProperties.defaults(), null,
                destinationUriResolver, inboundUriResolver, operationResolver));
        return restTemplate;
    }

    private static final String PAYLOAD = "{\"responseCode\":0,\"message\":\"OK\"}";

    private void assertCallSurvives(RestTemplate restTemplate) {
        assertThatCode(() -> {
            String body = restTemplate.getForObject(this.server.url("/accounts/7"), String.class);
            assertThat(body)
                    .as("the response must reach the caller byte-for-byte regardless")
                    .isEqualTo(PAYLOAD);
        }).doesNotThrowAnyException();
    }

    @Test
    void aDestinationUriResolverThatThrowsLosesTheDimensionButNotTheCall() {
        this.server.respondWith(200, PAYLOAD);

        assertCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver() {
                    @Override
                    public Resolved resolve(java.net.URI requestUri) {
                        throw new IllegalStateException("destination uri resolver exploded");
                    }
                },
                new com.bookit.servicecalllogging.uri.InboundUriResolver(),
                new com.bookit.servicecalllogging.operation.OperationResolver(),
                null, this.callLogger));
    }

    @Test
    void anInboundUriResolverThatThrowsLosesTheDimensionButNotTheCall() {
        this.server.respondWith(200, PAYLOAD);

        assertCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver(),
                new com.bookit.servicecalllogging.uri.InboundUriResolver() {
                    @Override
                    public String resolve() {
                        throw new IllegalStateException("inbound uri resolver exploded");
                    }
                },
                new com.bookit.servicecalllogging.operation.OperationResolver(),
                null, this.callLogger));
    }

    @Test
    void anOperationResolverThatThrowsLosesTheDimensionButNotTheCall() {
        this.server.respondWith(200, PAYLOAD);

        assertCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver(),
                new com.bookit.servicecalllogging.uri.InboundUriResolver(),
                new com.bookit.servicecalllogging.operation.OperationResolver() {
                    @Override
                    public String resolve(String headerValue) {
                        throw new IllegalStateException("operation resolver exploded");
                    }
                },
                null, this.callLogger));
    }

    @Test
    void aTimerRecordThatThrowsLosesTheMetricButNotTheCall() {
        this.server.respondWith(200, PAYLOAD);
        com.bookit.servicecalllogging.metrics.OutboundCallMetrics exploding =
                new com.bookit.servicecalllogging.metrics.OutboundCallMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        TestProperties.defaults()) {
                    @Override
                    public void record(String destination,
                                       com.bookit.servicecalllogging.metrics.Outcome outcome,
                                       String httpStatusGroup, String destinationUri,
                                       String inboundUri, String operation, long elapsedNanos) {
                        throw new IllegalStateException("timer exploded");
                    }
                };

        assertCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver(),
                new com.bookit.servicecalllogging.uri.InboundUriResolver(),
                new com.bookit.servicecalllogging.operation.OperationResolver(),
                exploding, this.callLogger));
    }

    @Test
    void aSendTimeLogEntryThatThrowsDoesNotPreventDispatchOrBreakTheCall() {
        // The riskiest of the new steps: it runs BEFORE the request is dispatched, so a failure
        // here could plausibly stop the call from happening at all.
        this.server.respondWith(200, PAYLOAD);
        CallLogger explodingOnRequest = new CallLogger() {
            @Override
            public void logRequest(OutboundCallRecord record) {
                throw new IllegalStateException("send-time entry exploded");
            }
        };

        assertCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver(),
                new com.bookit.servicecalllogging.uri.InboundUriResolver(),
                new com.bookit.servicecalllogging.operation.OperationResolver(),
                null, explodingOnRequest));
    }

    @Test
    void bothLogEntriesThrowingStillLeavesTheCallIntact() {
        this.server.respondWith(200, PAYLOAD);
        CallLogger explodingOnBoth = new CallLogger() {
            @Override
            public void logRequest(OutboundCallRecord record) {
                throw new IllegalStateException("send-time exploded");
            }

            @Override
            public void log(OutboundCallRecord record) {
                throw new IllegalStateException("response entry exploded");
            }
        };

        assertCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver(),
                new com.bookit.servicecalllogging.uri.InboundUriResolver(),
                new com.bookit.servicecalllogging.operation.OperationResolver(),
                null, explodingOnBoth));
    }

    @Test
    void everyNewStepThrowingAtOnceStillLeavesTheResponseByteForByteIdentical() {
        // The worst case: nothing about this feature works, and the call must not notice.
        this.server.respondWith(200, PAYLOAD);
        byte[] uninstrumented = new RestTemplate().getForObject(this.server.url("/accounts/7"), byte[].class);

        RestTemplate allBroken = withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver() {
                    @Override
                    public Resolved resolve(java.net.URI requestUri) {
                        throw new IllegalStateException("boom");
                    }
                },
                new com.bookit.servicecalllogging.uri.InboundUriResolver() {
                    @Override
                    public String resolve() {
                        throw new IllegalStateException("boom");
                    }
                },
                new com.bookit.servicecalllogging.operation.OperationResolver() {
                    @Override
                    public String resolve(String headerValue) {
                        throw new IllegalStateException("boom");
                    }
                },
                null,
                new CallLogger() {
                    @Override
                    public void logRequest(OutboundCallRecord record) {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public void log(OutboundCallRecord record) {
                        throw new IllegalStateException("boom");
                    }
                });

        byte[] viaBrokenStarter = allBroken.getForObject(this.server.url("/accounts/7"), byte[].class);

        assertThat(viaBrokenStarter).isEqualTo(uninstrumented);
    }

    @Test
    void aTransportFailureStillPropagatesUntouchedWithEveryNewStepBroken() throws IOException {
        String unreachable = com.bookit.servicecalllogging.testsupport.StubHttpServer.unreachableUrl();

        RestTemplate allBroken = withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver() {
                    @Override
                    public Resolved resolve(java.net.URI requestUri) {
                        throw new IllegalStateException("boom");
                    }
                },
                new com.bookit.servicecalllogging.uri.InboundUriResolver(),
                new com.bookit.servicecalllogging.operation.OperationResolver(),
                null, this.callLogger);

        assertThatThrownBy(() -> allBroken.getForObject(unreachable + "/x", String.class))
                .as("the original transport failure must reach the caller, not ours")
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
    }
}
