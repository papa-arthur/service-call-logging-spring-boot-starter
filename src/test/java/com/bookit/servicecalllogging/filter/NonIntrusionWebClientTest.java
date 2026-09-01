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
}
