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
}
