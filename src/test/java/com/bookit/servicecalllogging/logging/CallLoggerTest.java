package com.bookit.servicecalllogging.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CallLoggerTest {

    private final CallLogger callLogger = new CallLogger();

    @Test
    void logsEveryDeclaredField(CapturedOutput output) {
        callLogger.log(new OutboundCallRecord(
                "my-service", "payments-service:8080", "POST", 200, "2xx", 0, "OK", Instant.now()));

        assertThat(output).contains("source=my-service");
        assertThat(output).contains("destination=payments-service:8080");
        assertThat(output).contains("method=POST");
        assertThat(output).contains("httpStatus=200");
        assertThat(output).contains("httpStatusGroup=2xx");
        assertThat(output).contains("responseCode=0");
        assertThat(output).contains("responseMessage=OK");
    }

    @Test
    void absentResponseCodeIsRenderedAsAbsent(CapturedOutput output) {
        callLogger.log(new OutboundCallRecord(
                "my-service", "third-party:443", "GET", 200, "2xx", null, null, Instant.now()));

        assertThat(output).contains("responseCode=absent");
        assertThat(output).contains("responseMessage=absent");
    }

    @Test
    void missingHttpStatusIsRenderedAsNoneOnNetworkError(CapturedOutput output) {
        callLogger.log(new OutboundCallRecord(
                "my-service", "down-service:8080", "GET", null, "network-error", null, null, Instant.now()));

        assertThat(output).contains("httpStatus=none");
        assertThat(output).contains("httpStatusGroup=network-error");
    }

    @Test
    void logOutputNeverMentionsCredentialHeadersOrTheirValues(CapturedOutput output) {
        callLogger.log(new OutboundCallRecord(
                "my-service", "payments-service:8080", "POST", 200, "2xx", 0, "OK", Instant.now()));
        callLogger.logWarn("my-service", "payments-service:8080",
                new IllegalStateException("buffering failed"));

        assertThat(output).doesNotContain("Authorization");
        assertThat(output).doesNotContain("Cookie");
        assertThat(output).doesNotContain("Bearer");
    }

    @Test
    void instrumentationFailuresAreLoggedAtWarnWithoutBreakingAnything(CapturedOutput output) {
        callLogger.logWarn("my-service", "payments-service:8080",
                new IllegalStateException("peek exploded"));

        assertThat(output).contains("WARN");
        assertThat(output).contains("source=my-service");
        assertThat(output).contains("destination=payments-service:8080");
        assertThat(output).contains("IllegalStateException");
    }

    @Test
    void theMessageIsLoggedVerbatimWithNoLengthLimit(CapturedOutput output) {
        String longMessage = "Insufficient funds for transaction reference TXN-88213-AB; "
                + "retry after 24h".repeat(20);

        callLogger.log(new OutboundCallRecord(
                "my-service", "payments-service:8080", "POST", 200, "2xx", 1, longMessage, Instant.now()));

        assertThat(output).contains("responseMessage=" + longMessage);
    }

    @Test
    void aMessagePresentWithoutACodeStillReachesTheLogLine(CapturedOutput output) {
        callLogger.log(new OutboundCallRecord(
                "my-service", "svc:8080", "GET", 200, "2xx", null, "Declined", Instant.now()));

        assertThat(output).contains("responseCode=absent");
        assertThat(output).contains("responseMessage=Declined");
    }
}
