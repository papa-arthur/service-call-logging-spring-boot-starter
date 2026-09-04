package com.bookit.servicecalllogging.logging;

import com.bookit.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.bookit.servicecalllogging.testsupport.FakeClientHttpResponse;
import com.bookit.servicecalllogging.testsupport.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 003, T043 to T045 — one call, two separately identifiable telemetry entries.
 *
 * <p>The point of splitting the single entry is that a call which never comes back used to leave no
 * trace of having been attempted. The send-time entry fixes that, which is why it must be emitted
 * <em>before</em> dispatch rather than assembled afterwards.
 */
@ExtendWith(OutputCaptureExtension.class)
class TwoLogEntriesTest {

    private static final String REQUEST_PREFIX = "outbound-request";
    private static final String RESPONSE_PREFIX = "outbound-req-response";
    private static final String RETIRED_PREFIX = "outbound-call ";
    private static final String WARN_PREFIX = "outbound-call-instrumentation-error";

    private OutboundCallInterceptor interceptor(CallLogger logger) {
        return new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"),
                bytes -> Optional.of(0),
                logger,
                null,
                TestProperties.defaults());
    }

    private void call(CallLogger logger, String operation) throws Exception {
        MockClientHttpRequest request =
                new MockClientHttpRequest(HttpMethod.GET, URI.create("https://svc:8443/accounts/7"));
        if (operation != null) {
            request.getHeaders().set("X-Operation", operation);
        }
        interceptor(logger).intercept(request, new byte[0],
                (req, body) -> new FakeClientHttpResponse(
                        "{\"responseCode\":0,\"message\":\"OK\"}".getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> telemetryLines(CapturedOutput output) {
        return Arrays.stream(output.toString().split("\\R"))
                .filter(line -> line.contains(REQUEST_PREFIX) || line.contains(RESPONSE_PREFIX))
                .toList();
    }

    // ===== T043 =====

    @Test
    void oneCallProducesExactlyTwoTelemetryEntries(CapturedOutput output) throws Exception {
        call(new CallLogger(), "SendMoney");

        assertThat(telemetryLines(output)).hasSize(2);
    }

    @Test
    void theSendTimeEntryIsIdentifiableAndCarriesTheSendTimeContext(CapturedOutput output) throws Exception {
        call(new CallLogger(), "SendMoney");

        String sendTime = telemetryLines(output).stream()
                .filter(line -> line.contains(REQUEST_PREFIX))
                .findFirst().orElseThrow();

        assertThat(sendTime).contains("source=my-service");
        assertThat(sendTime).contains("destination=svc:8443");
        assertThat(sendTime).contains("method=GET");
        assertThat(sendTime).contains("destinationUri=/accounts/7");
        assertThat(sendTime).contains("inboundUri=unknown");
        assertThat(sendTime).contains("operation=SendMoney");
    }

    @Test
    void theSendTimeEntryCarriesNoStatusOrResponseCodeBecauseNeitherExistsYet(CapturedOutput output)
            throws Exception {
        call(new CallLogger(), "SendMoney");

        String sendTime = telemetryLines(output).stream()
                .filter(line -> line.contains(REQUEST_PREFIX))
                .findFirst().orElseThrow();

        assertThat(sendTime).doesNotContain("httpStatus=", "responseCode=", "responseMessage=");
    }

    @Test
    void theResponseEntryAddsTheObservedResponseCode(CapturedOutput output) throws Exception {
        call(new CallLogger(), "SendMoney");

        String response = telemetryLines(output).stream()
                .filter(line -> line.contains(RESPONSE_PREFIX))
                .findFirst().orElseThrow();

        assertThat(response).contains("httpStatus=200");
        assertThat(response).contains("responseCode=0");
        assertThat(response).contains("operation=SendMoney");
        assertThat(response).contains("destinationUri=/accounts/7");
    }

    @Test
    void theSendTimeEntryIsEmittedBeforeTheResponseEntry(CapturedOutput output) throws Exception {
        call(new CallLogger(), "SendMoney");

        List<String> lines = telemetryLines(output);
        assertThat(lines.get(0)).contains(REQUEST_PREFIX);
        assertThat(lines.get(1)).contains(RESPONSE_PREFIX);
    }

    @Test
    void aCallThatFailsBeforeAnyResponseStillProducesBothEntries(CapturedOutput output) {
        // The behaviour the split exists for: the attempt is on record even though nothing
        // came back (FR-034).
        assertThatThrownBy(() -> interceptor(new CallLogger()).intercept(
                new MockClientHttpRequest(HttpMethod.GET, URI.create("https://svc:8443/x")),
                new byte[0],
                (req, body) -> {
                    throw new IOException("connection refused");
                })).isInstanceOf(IOException.class);

        List<String> lines = telemetryLines(output);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains(REQUEST_PREFIX);
        assertThat(lines.get(1)).contains(RESPONSE_PREFIX).contains("responseCode=absent");
    }

    @Test
    void neitherTelemetryEntryUsesTheRetiredNaming(CapturedOutput output) throws Exception {
        call(new CallLogger(), "SendMoney");

        assertThat(telemetryLines(output))
                .allSatisfy(line -> assertThat(line).doesNotContain(RETIRED_PREFIX));
    }

    // ===== T044: the warning entry is untouched and uncounted =====

    @Test
    void theInstrumentationFailureWarningKeepsItsExactTextAndIsNotOneOfTheTwo(CapturedOutput output) {
        // The requirement most at risk from an over-eager prefix rename (FR-030, FR-033): this
        // entry is not per-call telemetry and is the only signal that instrumentation degraded.
        new CallLogger().logWarn("my-service", "svc:8443", new IllegalStateException("boom"));

        assertThat(output).contains(WARN_PREFIX);
        assertThat(telemetryLines(output))
                .as("the warning entry must not be counted among the two telemetry entries")
                .isEmpty();
    }

    @Test
    void theWarningPrefixStillBeginsWithTheRetiredCharactersOnPurpose() {
        // A naive `startsWith("outbound-call")` check would now match only the warning. That is
        // documented behaviour, not an oversight — the warning was deliberately left alone.
        assertThat(WARN_PREFIX).startsWith("outbound-call");
        assertThat(REQUEST_PREFIX).doesNotStartWith("outbound-call");
        assertThat(RESPONSE_PREFIX).doesNotStartWith("outbound-call");
    }

    // ===== SC-004: every dimension present on both entries, whatever the inputs =====

    @Test
    void bothEntriesCarryAllThreeDimensionsAcrossTheFullFallbackMatrix(CapturedOutput output)
            throws Exception {
        // No inbound request, an untemplatable destination, and no operation header at once.
        call(new CallLogger(), null);

        assertThat(telemetryLines(output))
                .hasSize(2)
                .allSatisfy(line -> {
                    assertThat(line).contains("destinationUri=/accounts/7");
                    assertThat(line).contains("inboundUri=unknown");
                    assertThat(line).contains("operation=undefined");
                });
    }
}
