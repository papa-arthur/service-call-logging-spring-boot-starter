package com.bookit.servicecalllogging.interceptor;

import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.logging.OutboundCallRecord;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.bookit.servicecalllogging.testsupport.FakeClientHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboundCallInterceptorTest {

    private static final ServiceCallLoggingProperties DEFAULTS = new ServiceCallLoggingProperties(
            true, "X-Source-Service", "X-Destination-Service", "service_name", 1_048_576,
            com.bookit.servicecalllogging.testsupport.TestProperties.defaultMetrics(),
            java.util.List.of());

    private RecordingCallLogger callLogger;
    private OutboundCallInterceptor interceptor;

    @BeforeEach
    void setUp() {
        this.callLogger = new RecordingCallLogger();
        this.interceptor = new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"),
                new SimpleJsonExtractor(),
                this.callLogger,
                DEFAULTS);
    }

    @Test
    void stampsBothCorrelationHeadersOnTheOutgoingRequest() throws Exception {
        MockClientHttpRequest request = request("https://payments.internal:8443/charge");

        this.interceptor.intercept(request, new byte[0],
                (req, body) -> new FakeClientHttpResponse("{\"responseCode\":0}".getBytes(StandardCharsets.UTF_8)));

        assertThat(request.getHeaders().getFirst("X-Source-Service")).isEqualTo("my-service");
        assertThat(request.getHeaders().getFirst("X-Destination-Service")).isEqualTo("payments.internal:8443");
    }

    @Test
    void honoursConfiguredHeaderNames() throws Exception {
        ServiceCallLoggingProperties custom = new ServiceCallLoggingProperties(
                true, "X-From", "X-To", "service_name", 1_048_576, DEFAULTS.metrics(),
                java.util.List.of());
        OutboundCallInterceptor customInterceptor = new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"), new SimpleJsonExtractor(),
                this.callLogger, custom);
        MockClientHttpRequest request = request("http://svc:8080/x");

        customInterceptor.intercept(request, new byte[0],
                (req, body) -> new FakeClientHttpResponse(new byte[0]));

        assertThat(request.getHeaders().getFirst("X-From")).isEqualTo("my-service");
        assertThat(request.getHeaders().getFirst("X-To")).isEqualTo("svc:8080");
        assertThat(request.getHeaders().containsKey("X-Source-Service")).isFalse();
    }

    @Test
    void serviceNameHintHeaderOverridesUrlDerivedDestination() throws Exception {
        MockClientHttpRequest request = request("http://10.0.0.5:8080/x");
        request.getHeaders().add("service_name", "billing-service");

        this.interceptor.intercept(request, new byte[0],
                (req, body) -> new FakeClientHttpResponse(new byte[0]));

        assertThat(request.getHeaders().getFirst("X-Destination-Service")).isEqualTo("billing-service");
        assertThat(this.callLogger.records).singleElement()
                .satisfies(r -> assertThat(r.destination()).isEqualTo("billing-service"));
    }

    @Test
    void forwardsTheParsedResponseCodeAndStatusGroupToTheLogger() throws Exception {
        this.interceptor.intercept(request("http://svc:8080/x"), new byte[0],
                (req, body) -> new FakeClientHttpResponse(
                        HttpStatus.OK, "{\"responseCode\":7}".getBytes(StandardCharsets.UTF_8)));

        assertThat(this.callLogger.records).singleElement().satisfies(record -> {
            assertThat(record.source()).isEqualTo("my-service");
            assertThat(record.destination()).isEqualTo("svc:8080");
            assertThat(record.httpMethod()).isEqualTo("GET");
            assertThat(record.httpStatusCode()).isEqualTo(200);
            assertThat(record.httpStatusGroup()).isEqualTo("2xx");
            assertThat(record.responseCode()).isEqualTo(7);
            assertThat(record.timestamp()).isNotNull();
        });
    }

    @Test
    void classifiesEveryHttpStatusGroup() throws Exception {
        assertThat(statusGroupFor(HttpStatus.OK)).isEqualTo("2xx");
        assertThat(statusGroupFor(HttpStatus.NOT_FOUND)).isEqualTo("4xx");
        assertThat(statusGroupFor(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo("5xx");
        assertThat(statusGroupFor(HttpStatus.MOVED_PERMANENTLY)).isEqualTo("3xx");
    }

    @Test
    void absentResponseCodeIsLoggedAsNull() throws Exception {
        this.interceptor.intercept(request("http://svc:8080/x"), new byte[0],
                (req, body) -> new FakeClientHttpResponse("not json".getBytes(StandardCharsets.UTF_8)));

        assertThat(this.callLogger.records).singleElement()
                .satisfies(r -> assertThat(r.responseCode()).isNull());
    }

    @Test
    void loggerFailureNeverReachesTheCallerAndTheBodyStaysIntact() throws Exception {
        byte[] payload = "{\"responseCode\":0}".getBytes(StandardCharsets.UTF_8);
        OutboundCallInterceptor withBrokenLogger = new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"), new SimpleJsonExtractor(),
                new CallLogger() {
                    @Override
                    public void log(OutboundCallRecord record) {
                        throw new IllegalStateException("logger exploded");
                    }
                },
                DEFAULTS);

        var response = withBrokenLogger.intercept(request("http://svc:8080/x"), new byte[0],
                (req, body) -> new FakeClientHttpResponse(payload));

        assertThat(response.getBody().readAllBytes()).isEqualTo(payload);
    }

    @Test
    void ioExceptionFromTheCallStillPropagatesToTheCaller() {
        assertThatThrownBy(() -> this.interceptor.intercept(request("http://svc:8080/x"), new byte[0],
                (req, body) -> {
                    throw new IOException("connection refused");
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("connection refused");
    }

    // ===== spec 003 (T038) — elapsed duration is recorded even without a response =====

    @Test
    void latencyIsRecordedOnASuccessfulCall() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        OutboundCallInterceptor instrumented = new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"), new SimpleJsonExtractor(),
                this.callLogger,
                new com.bookit.servicecalllogging.metrics.OutboundCallMetrics(registry, DEFAULTS),
                DEFAULTS);

        instrumented.intercept(request("http://svc:8080/x"), new byte[0],
                (req, body) -> new FakeClientHttpResponse("{\"responseCode\":0}".getBytes(StandardCharsets.UTF_8)));

        io.micrometer.core.instrument.Timer timer =
                registry.find("http.outbound.calls.latency").timer();
        assertThat(timer).as("the latency timer must be recorded").isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void latencyIsStillRecordedWhenTheCallFailsBeforeAnyResponse() {
        // FR-016 — a call that never produced a response still contributes its elapsed time.
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        OutboundCallInterceptor instrumented = new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"), new SimpleJsonExtractor(),
                this.callLogger,
                new com.bookit.servicecalllogging.metrics.OutboundCallMetrics(registry, DEFAULTS),
                DEFAULTS);

        assertThatThrownBy(() -> instrumented.intercept(request("http://svc:8080/x"), new byte[0],
                (req, body) -> {
                    throw new IOException("connection refused");
                })).isInstanceOf(IOException.class);

        io.micrometer.core.instrument.Timer timer = registry.find("http.outbound.calls.latency")
                .tag("http_status_group", "network-error")
                .timer();
        assertThat(timer).as("elapsed time must be recorded on the failure path too").isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void networkFailureIsLoggedAsNetworkErrorWithNoHttpStatus() {
        assertThatThrownBy(() -> this.interceptor.intercept(request("http://svc:8080/x"), new byte[0],
                (req, body) -> {
                    throw new IOException("connection refused");
                }))
                .isInstanceOf(IOException.class);

        assertThat(this.callLogger.records).singleElement().satisfies(record -> {
            assertThat(record.httpStatusGroup()).isEqualTo("network-error");
            assertThat(record.httpStatusCode()).isNull();
            assertThat(record.responseCode()).isNull();
        });
    }

    private String statusGroupFor(HttpStatus status) throws IOException {
        this.callLogger.records.clear();
        this.interceptor.intercept(request("http://svc:8080/x"), new byte[0],
                (req, body) -> new FakeClientHttpResponse(status, new byte[0]));
        return this.callLogger.records.get(0).httpStatusGroup();
    }

    private static MockClientHttpRequest request(String uri) {
        return new MockClientHttpRequest(HttpMethod.GET, URI.create(uri));
    }

    private static final class RecordingCallLogger extends CallLogger {
        private final List<OutboundCallRecord> records = new ArrayList<>();

        @Override
        public void log(OutboundCallRecord record) {
            this.records.add(record);
        }
    }

    private static final class SimpleJsonExtractor implements ResponseCodeExtractor {
        @Override
        public Optional<Integer> extract(byte[] bodyBytes) {
            String text = new String(bodyBytes, StandardCharsets.UTF_8);
            int index = text.indexOf("\"responseCode\":");
            if (index < 0) {
                return Optional.empty();
            }
            int end = index + 15;
            StringBuilder digits = new StringBuilder();
            while (end < text.length() && Character.isDigit(text.charAt(end))) {
                digits.append(text.charAt(end++));
            }
            return digits.length() == 0 ? Optional.empty() : Optional.of(Integer.parseInt(digits.toString()));
        }
    }
}
