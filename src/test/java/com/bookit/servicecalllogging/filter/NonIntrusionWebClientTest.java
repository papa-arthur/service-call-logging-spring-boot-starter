package com.bookit.servicecalllogging.filter;

import com.bookit.servicecalllogging.EnvelopeFieldExtractor;
import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import com.bookit.servicecalllogging.extractor.JacksonEnvelopeFieldExtractor;
import com.bookit.servicecalllogging.extractor.JacksonResponseCodeExtractor;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.bookit.servicecalllogging.testsupport.RecordingCallLogger;
import com.bookit.servicecalllogging.testsupport.StubHttpServer;
import com.bookit.servicecalllogging.testsupport.TestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Constitution Principle I — the Non-Intrusion merge gate for the reactive path.
 */
@Tag("non-intrusion")
class NonIntrusionWebClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

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

    /**
     * WebClient's own {@code maxInMemorySize} defaults to 256 KB and is entirely independent of
     * this starter's {@code max-body-bytes}. It is raised here so the oversized-body test
     * exercises <em>our</em> cap rather than tripping WebClient's codec limit first — exactly
     * what a consumer handling large responses has to do with or without the starter.
     */
    private static final int TEST_CODEC_LIMIT = 4 * 1024 * 1024;

    private static WebClient.Builder builderWithRoomForLargeBodies() {
        return WebClient.builder().exchangeStrategies(ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(TEST_CODEC_LIMIT))
                .build());
    }

    private WebClient instrumented(ResponseCodeExtractor extractor, int cap) {
        return builderWithRoomForLargeBodies()
                .filter(new OutboundCallExchangeFilter(
                        new DestinationNameResolver("my-service"), extractor, this.callLogger,
                        TestProperties.withMaxBodyBytes(cap)))
                .build();
    }

    private WebClient instrumented() {
        return instrumented(new JacksonResponseCodeExtractor(), 1_048_576);
    }

    // ---- 1. body byte-for-byte identical -----------------------------------------------

    @Test
    void instrumentedResponseIsByteForByteIdenticalToAnUninstrumentedOne() {
        String payload = "{\"responseCode\":0,\"data\":{\"id\":42,\"name\":\"widget\"}}";
        this.server.respondWith(200, payload);

        byte[] uninstrumented = builderWithRoomForLargeBodies().build().get().uri(this.server.url("/x"))
                .retrieve().bodyToMono(byte[].class).block(TIMEOUT);
        byte[] viaStarter = instrumented().get().uri(this.server.url("/x"))
                .retrieve().bodyToMono(byte[].class).block(TIMEOUT);

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

        String body = instrumented(exploding, 1_048_576).get().uri(this.server.url("/x"))
                .retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(body).isEqualTo(payload);
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }

    // ---- 3. empty body ---------------------------------------------------------------

    @Test
    void anEmptyBodyDegradesToAbsentAndTheCallSucceeds() {
        this.server.respondWithEmptyBody(200);

        String body = instrumented().get().uri(this.server.url("/x"))
                .retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(body).isNull();
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
        assertThat(this.callLogger.onlyRecord().httpStatusGroup()).isEqualTo("2xx");
    }

    // ---- 4. body larger than the cap -------------------------------------------------

    @Test
    void anOversizedBodyDegradesToAbsentAndEveryByteStillReachesTheCaller() {
        byte[] payload = new byte[1_400_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('a' + (i % 26));
        }
        this.server.respondWith(200, payload);

        byte[] body = instrumented().get().uri(this.server.url("/x"))
                .retrieve()
                .bodyToMono(byte[].class)
                .block(TIMEOUT);

        assertThat(body).hasSize(payload.length);
        assertThat(body).isEqualTo(payload);
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }

    // ---- 5. network error is re-propagated unchanged ----------------------------------

    @Test
    void aNetworkErrorIsRePropagatedToTheCallerUnchanged() throws IOException {
        String unreachable = StubHttpServer.unreachableUrl();

        assertThatThrownBy(() -> instrumented().get().uri(unreachable + "/x")
                .retrieve().bodyToMono(String.class).block(TIMEOUT))
                .isInstanceOf(WebClientRequestException.class);

        assertThat(this.callLogger.onlyRecord().httpStatusGroup()).isEqualTo("network-error");
        assertThat(this.callLogger.onlyRecord().httpStatusCode()).isNull();
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }

    // ---- extra: non-JSON body --------------------------------------------------------

    @Test
    void aNonJsonBodyDegradesToAbsentAndIsReturnedIntact() {
        String payload = "<html><body>totally not json</body></html>";
        this.server.contentType("text/html").respondWith(200, payload);

        String body = instrumented().get().uri(this.server.url("/x"))
                .retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(body).isEqualTo(payload);
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }

    // ---- extra: extractor returning null (contract violation) -------------------------

    @Test
    void anExtractorReturningNullIsToleratedWithoutBreakingTheCall() {
        String payload = "{\"responseCode\":0}";
        this.server.respondWith(200, payload);

        String body = instrumented(bytes -> null, 1_048_576).get().uri(this.server.url("/x"))
                .retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(body).isEqualTo(payload);
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }

    // ---- the envelope matching engine fails ---------------------------------------------

    private WebClient instrumentedWithEnvelope(EnvelopeFieldExtractor envelopeFieldExtractor) {
        return builderWithRoomForLargeBodies()
                .filter(new OutboundCallExchangeFilter(
                        new DestinationNameResolver("my-service"), new JacksonResponseCodeExtractor(),
                        this.callLogger, null, TestProperties.defaults(), envelopeFieldExtractor))
                .build();
    }

    @Test
    void anExplodingEnvelopeExtractorLosesOnlyTheMessageAndNeverTheCall() {
        String payload = "{\"responseCode\":0,\"message\":\"OK\"}";
        this.server.respondWith(200, payload);

        String body = instrumentedWithEnvelope(bytes -> {
            throw new IllegalStateException("envelope extractor exploded");
        }).get().uri(this.server.url("/x")).retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(body).isEqualTo(payload);
        assertThat(this.callLogger.onlyRecord().responseCode()).isEqualTo(0);
        assertThat(this.callLogger.onlyRecord().message()).isNull();
    }

    @Test
    void aMalformedEnvelopeConfigurationStillInstrumentsAndNeverBreaksTheCall() {
        String payload = "{\"responseCode\":0,\"message\":\"OK\"}";
        this.server.respondWith(200, payload);

        List<ServiceCallLoggingProperties.Envelope> malformed = Arrays.asList(
                null, new ServiceCallLoggingProperties.Envelope("   ", "  ", 0));

        String body = instrumentedWithEnvelope(new JacksonEnvelopeFieldExtractor(malformed))
                .get().uri(this.server.url("/x")).retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(body).isEqualTo(payload);
        assertThat(this.callLogger.onlyRecord().responseCode()).isEqualTo(0);
    }

    // ---- spec 003 (T050): the same per-step failure matrix, reactive ------------------

    private static final String SPEC003_PAYLOAD = "{\"responseCode\":0,\"message\":\"OK\"}";

    /** Injects the spec-003 collaborators so each new step can be broken independently. */
    private WebClient withCollaborators(
            com.bookit.servicecalllogging.uri.DestinationUriResolver destinationUriResolver,
            com.bookit.servicecalllogging.uri.InboundUriResolver inboundUriResolver,
            com.bookit.servicecalllogging.operation.OperationResolver operationResolver,
            com.bookit.servicecalllogging.metrics.OutboundCallMetrics metrics,
            com.bookit.servicecalllogging.logging.CallLogger logger) {
        return builderWithRoomForLargeBodies()
                .filter(new OutboundCallExchangeFilter(
                        new DestinationNameResolver("my-service"), new JacksonResponseCodeExtractor(),
                        logger, metrics, TestProperties.defaults(), null,
                        destinationUriResolver, inboundUriResolver, operationResolver))
                .build();
    }

    private void assertReactiveCallSurvives(WebClient webClient) {
        assertThatCode(() -> {
            String body = webClient.get().uri(this.server.url("/accounts/7"))
                    .retrieve().bodyToMono(String.class).block(java.time.Duration.ofSeconds(20));
            assertThat(body).isEqualTo(SPEC003_PAYLOAD);
        }).doesNotThrowAnyException();
    }

    @Test
    void aDestinationUriResolverThatThrowsLosesTheDimensionButNotTheReactiveCall() {
        this.server.respondWith(200, SPEC003_PAYLOAD);

        assertReactiveCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver() {
                    @Override
                    public Resolved resolve(java.net.URI requestUri) {
                        throw new IllegalStateException("boom");
                    }
                },
                new com.bookit.servicecalllogging.uri.InboundUriResolver(),
                new com.bookit.servicecalllogging.operation.OperationResolver(),
                null, this.callLogger));
    }

    @Test
    void anInboundUriResolverThatThrowsLosesTheDimensionButNotTheReactiveCall() {
        this.server.respondWith(200, SPEC003_PAYLOAD);

        assertReactiveCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver(),
                new com.bookit.servicecalllogging.uri.InboundUriResolver() {
                    @Override
                    public String resolve() {
                        throw new IllegalStateException("boom");
                    }
                },
                new com.bookit.servicecalllogging.operation.OperationResolver(),
                null, this.callLogger));
    }

    @Test
    void anOperationResolverThatThrowsLosesTheDimensionButNotTheReactiveCall() {
        this.server.respondWith(200, SPEC003_PAYLOAD);

        assertReactiveCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver(),
                new com.bookit.servicecalllogging.uri.InboundUriResolver(),
                new com.bookit.servicecalllogging.operation.OperationResolver() {
                    @Override
                    public String resolve(String headerValue) {
                        throw new IllegalStateException("boom");
                    }
                },
                null, this.callLogger));
    }

    @Test
    void aTimerRecordThatThrowsLosesTheMetricButNotTheReactiveCall() {
        this.server.respondWith(200, SPEC003_PAYLOAD);
        com.bookit.servicecalllogging.metrics.OutboundCallMetrics exploding =
                new com.bookit.servicecalllogging.metrics.OutboundCallMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                        TestProperties.defaults()) {
                    @Override
                    public void record(String destination,
                                       com.bookit.servicecalllogging.metrics.Outcome outcome,
                                       String httpStatusGroup, String destinationUri,
                                       String inboundUri, String operation, long elapsedNanos) {
                        throw new IllegalStateException("boom");
                    }
                };

        assertReactiveCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver(),
                new com.bookit.servicecalllogging.uri.InboundUriResolver(),
                new com.bookit.servicecalllogging.operation.OperationResolver(),
                exploding, this.callLogger));
    }

    @Test
    void aSendTimeEntryThatThrowsDoesNotPreventTheExchange() {
        this.server.respondWith(200, SPEC003_PAYLOAD);

        assertReactiveCallSurvives(withCollaborators(
                new com.bookit.servicecalllogging.uri.DestinationUriResolver(),
                new com.bookit.servicecalllogging.uri.InboundUriResolver(),
                new com.bookit.servicecalllogging.operation.OperationResolver(),
                null,
                new com.bookit.servicecalllogging.logging.CallLogger() {
                    @Override
                    public void logRequest(
                            com.bookit.servicecalllogging.logging.OutboundCallRecord record) {
                        throw new IllegalStateException("boom");
                    }
                }));
    }

    @Test
    void everyNewStepThrowingAtOnceLeavesTheReactiveResponseByteForByteIdentical() {
        this.server.respondWith(200, SPEC003_PAYLOAD);
        byte[] uninstrumented = builderWithRoomForLargeBodies().build()
                .get().uri(this.server.url("/accounts/7"))
                .retrieve().bodyToMono(byte[].class).block(java.time.Duration.ofSeconds(20));

        WebClient allBroken = withCollaborators(
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
                new com.bookit.servicecalllogging.logging.CallLogger() {
                    @Override
                    public void logRequest(
                            com.bookit.servicecalllogging.logging.OutboundCallRecord record) {
                        throw new IllegalStateException("boom");
                    }

                    @Override
                    public void log(com.bookit.servicecalllogging.logging.OutboundCallRecord record) {
                        throw new IllegalStateException("boom");
                    }
                });

        byte[] viaBrokenStarter = allBroken.get().uri(this.server.url("/accounts/7"))
                .retrieve().bodyToMono(byte[].class).block(java.time.Duration.ofSeconds(20));

        assertThat(viaBrokenStarter).isEqualTo(uninstrumented);
    }
}
