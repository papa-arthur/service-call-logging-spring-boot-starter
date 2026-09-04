package com.telecelghana.play.app.common.servicecalllogging.filter;

import com.telecelghana.play.app.common.servicecalllogging.ResponseCodeExtractor;
import com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties;
import com.telecelghana.play.app.common.servicecalllogging.extractor.JacksonResponseCodeExtractor;
import com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.RecordingCallLogger;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.StubHttpServer;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.TestProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural tests for the reactive filter, driven through a real {@link WebClient} against a
 * JDK {@code HttpServer} stub — the same stubbing pattern as the blocking integration tests,
 * with no extra test dependencies (research.md D-009).
 */
class OutboundCallExchangeFilterTest {

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

    private WebClient client(ResponseCodeExtractor extractor, ServiceCallLoggingProperties properties) {
        return WebClient.builder()
                .filter(new OutboundCallExchangeFilter(
                        new DestinationNameResolver("my-service"), extractor, this.callLogger, properties))
                .build();
    }

    private WebClient client() {
        return client(new JacksonResponseCodeExtractor(), TestProperties.defaults());
    }

    @Test
    void theMutatedRequestCarriesBothCorrelationHeadersOnTheWire() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        client().get().uri(this.server.url("/x")).retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(this.server.lastRequestHeader("X-Source-Service")).isEqualTo("my-service");
        assertThat(this.server.lastRequestHeader("X-Destination-Service"))
                .isEqualTo("127.0.0.1:" + this.server.port());
    }

    @Test
    void configuredHeaderNamesAreHonoured() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        client(new JacksonResponseCodeExtractor(), TestProperties.withHeaderNames("X-From", "X-To"))
                .get().uri(this.server.url("/x")).retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(this.server.lastRequestHeader("X-From")).isEqualTo("my-service");
        assertThat(this.server.lastRequestHeader("X-To")).isEqualTo("127.0.0.1:" + this.server.port());
        assertThat(this.server.lastRequestHeader("X-Source-Service")).isNull();
    }

    @Test
    void theHintHeaderSetByTheCallerOverridesTheUrlDerivedDestination() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        client().get().uri(this.server.url("/x"))
                .header("service_name", "billing-service")
                .retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(this.server.lastRequestHeader("X-Destination-Service")).isEqualTo("billing-service");
        assertThat(this.callLogger.onlyRecord().destination()).isEqualTo("billing-service");
    }

    @Test
    void everyBodyBufferIsReEmittedToTheFinalCallerSubscriber() {
        StringBuilder payload = new StringBuilder("{\"responseCode\":0,\"filler\":\"");
        payload.append("x".repeat(200_000)).append("\"}");
        this.server.respondWith(200, payload.toString());

        String body = client().get().uri(this.server.url("/x"))
                .retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(body).isEqualTo(payload.toString());
        assertThat(this.callLogger.onlyRecord().responseCode()).isEqualTo(0);
    }

    @Test
    void anExtractorExceptionForwardsAnAbsentResultToTheLogger() {
        this.server.respondWith(200, "{\"responseCode\":0}");
        ResponseCodeExtractor exploding = bytes -> {
            throw new IllegalStateException("boom");
        };

        String body = client(exploding, TestProperties.defaults())
                .get().uri(this.server.url("/x")).retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(body).isEqualTo("{\"responseCode\":0}");
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }

    @Test
    void networkErrorsArePropagatedUnchangedAndRecordedAsNetworkError() throws IOException {
        String unreachable = StubHttpServer.unreachableUrl();

        assertThatThrownBy(() -> client().get().uri(unreachable + "/x")
                .retrieve().bodyToMono(String.class).block(TIMEOUT))
                .isInstanceOf(WebClientRequestException.class);

        assertThat(this.callLogger.onlyRecord().httpStatusGroup()).isEqualTo("network-error");
    }

    // ===== spec 003 (T038) — elapsed duration on the reactive path =====

    private WebClient instrumentedClient(io.micrometer.core.instrument.MeterRegistry registry) {
        return WebClient.builder()
                .filter(new OutboundCallExchangeFilter(
                        new DestinationNameResolver("my-service"), new JacksonResponseCodeExtractor(),
                        this.callLogger,
                        new com.telecelghana.play.app.common.servicecalllogging.metrics.OutboundCallMetrics(
                                registry, TestProperties.defaults()),
                        TestProperties.defaults()))
                .build();
    }

    @Test
    void latencyIsRecordedOnTheReactivePath() {
        this.server.respondWith(200, "{\"responseCode\":0}");
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        instrumentedClient(registry).get().uri(this.server.url("/x"))
                .retrieve().bodyToMono(String.class).block(TIMEOUT);

        io.micrometer.core.instrument.Timer timer =
                registry.find("http.outbound.calls.latency").timer();
        assertThat(timer).as("the latency timer must be recorded on the reactive path").isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void latencyIsStillRecordedWhenTheReactiveCallFailsBeforeAnyResponse() throws IOException {
        // FR-016 — the onErrorResume path must contribute its elapsed time too.
        String unreachable = StubHttpServer.unreachableUrl();
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

        assertThatThrownBy(() -> instrumentedClient(registry).get().uri(unreachable + "/x")
                .retrieve().bodyToMono(String.class).block(TIMEOUT))
                .isInstanceOf(WebClientRequestException.class);

        io.micrometer.core.instrument.Timer timer = registry.find("http.outbound.calls.latency")
                .tag("http_status_group", "network-error")
                .timer();
        assertThat(timer).as("elapsed time must be recorded on the reactive failure path").isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void statusGroupsAreClassifiedFromTheReactiveResponse() {
        this.server.respondWith(404, "{\"responseCode\":1}");
        exchangeIgnoringStatus(this.server.url("/missing"));
        assertThat(this.callLogger.onlyRecord().httpStatusGroup()).isEqualTo("4xx");
        assertThat(this.callLogger.onlyRecord().httpStatusCode()).isEqualTo(404);
        this.callLogger.reset();

        this.server.respondWith(503, "{\"responseCode\":1}");
        exchangeIgnoringStatus(this.server.url("/down"));
        assertThat(this.callLogger.onlyRecord().httpStatusGroup()).isEqualTo("5xx");
        assertThat(this.callLogger.onlyRecord().httpStatusCode()).isEqualTo(503);
    }

    /** Reads the body without WebClient's default 4xx/5xx error signalling getting in the way. */
    private String exchangeIgnoringStatus(String url) {
        return client().get().uri(url)
                .exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty(""))
                .block(TIMEOUT);
    }

    @Test
    void aCapOfZeroSkipsParsingButStillDeliversTheBody() {
        this.server.respondWith(200, "{\"responseCode\":0}");

        String body = client(new JacksonResponseCodeExtractor(), TestProperties.withMaxBodyBytes(0))
                .get().uri(this.server.url("/x")).retrieve().bodyToMono(String.class).block(TIMEOUT);

        assertThat(body).isEqualTo("{\"responseCode\":0}");
        assertThat(this.callLogger.onlyRecord().responseCode()).isNull();
    }
}
