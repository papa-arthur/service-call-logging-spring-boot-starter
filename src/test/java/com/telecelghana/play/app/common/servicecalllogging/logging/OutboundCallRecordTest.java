package com.telecelghana.play.app.common.servicecalllogging.logging;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundCallRecordTest {

    @Test
    void constructorSetsEveryFieldAndAccessorsReturnThem() {
        Instant timestamp = Instant.parse("2026-08-27T10:15:30Z");

        OutboundCallRecord record = new OutboundCallRecord(
                "my-service",
                "payments-service:8080",
                "POST",
                200,
                "2xx",
                0,
                "OK",
                "unknown",
                "unknown",
                "undefined",
                timestamp);

        assertThat(record.source()).isEqualTo("my-service");
        assertThat(record.destination()).isEqualTo("payments-service:8080");
        assertThat(record.httpMethod()).isEqualTo("POST");
        assertThat(record.httpStatusCode()).isEqualTo(200);
        assertThat(record.httpStatusGroup()).isEqualTo("2xx");
        assertThat(record.responseCode()).isEqualTo(0);
        assertThat(record.message()).isEqualTo("OK");
        assertThat(record.timestamp()).isEqualTo(timestamp);
    }

    @Test
    void nullableFieldsAcceptNullWithoutError() {
        OutboundCallRecord record = new OutboundCallRecord(
                "my-service",
                "auth-service:443",
                "GET",
                null,
                "network-error",
                null,
                null,
                "unknown", "unknown", "undefined", Instant.now());

        assertThat(record.httpStatusCode()).isNull();
        assertThat(record.responseCode()).isNull();
        assertThat(record.message()).isNull();
        assertThat(record.timestamp()).isNotNull();
        assertThat(record.httpStatusGroup()).isEqualTo("network-error");
    }

    @Test
    void theMessageIsCarriedIndependentlyOfTheResponseCode() {
        OutboundCallRecord codeWithoutMessage = new OutboundCallRecord(
                "my-service", "svc:8080", "GET", 200, "2xx", 0, null, "unknown", "unknown", "undefined", Instant.now());
        OutboundCallRecord messageWithoutCode = new OutboundCallRecord(
                "my-service", "svc:8080", "GET", 200, "2xx", null, "Declined", "unknown", "unknown", "undefined", Instant.now());

        assertThat(codeWithoutMessage.responseCode()).isEqualTo(0);
        assertThat(codeWithoutMessage.message()).isNull();
        assertThat(messageWithoutCode.responseCode()).isNull();
        assertThat(messageWithoutCode.message()).isEqualTo("Declined");
    }

    // ===== spec 003 (T011) — the three fields the Principle VII expansion admits =====

    @Test
    void theThreeNewFieldsAreCarriedAsGiven() {
        OutboundCallRecord record = new OutboundCallRecord(
                "my-service", "svc:8080", "GET", 200, "2xx", 0, "OK",
                "/accounts/{id}/transfers", "/api/v1/payments", "SendMoney", Instant.now());

        assertThat(record.destinationUri()).isEqualTo("/accounts/{id}/transfers");
        assertThat(record.inboundUri()).isEqualTo("/api/v1/payments");
        assertThat(record.operation()).isEqualTo("SendMoney");
    }

    @Test
    void nullOrBlankNewFieldsNormaliseToTheirDocumentedFallbacks() {
        // FR-009, FR-019, SC-004 — these dimensions are never absent and never empty, so a log
        // line's field count is constant however the record was built.
        OutboundCallRecord nulls = new OutboundCallRecord(
                "my-service", "svc:8080", "GET", 200, "2xx", 0, "OK",
                null, null, null, Instant.now());
        OutboundCallRecord blanks = new OutboundCallRecord(
                "my-service", "svc:8080", "GET", 200, "2xx", 0, "OK",
                "", "   ", "\t", Instant.now());

        for (OutboundCallRecord record : java.util.List.of(nulls, blanks)) {
            assertThat(record.destinationUri()).isEqualTo(OutboundCallRecord.UNKNOWN_URI);
            assertThat(record.inboundUri()).isEqualTo(OutboundCallRecord.UNKNOWN_URI);
            assertThat(record.operation()).isEqualTo(OutboundCallRecord.UNDEFINED_OPERATION);
        }
    }

    @Test
    void theDocumentedFallbackLiteralsAreExactlyUnknownAndUndefined() {
        // An operator reads these on dashboards, so the literals are contract, not detail.
        assertThat(OutboundCallRecord.UNKNOWN_URI).isEqualTo("unknown");
        assertThat(OutboundCallRecord.UNDEFINED_OPERATION).isEqualTo("undefined");
    }
}
