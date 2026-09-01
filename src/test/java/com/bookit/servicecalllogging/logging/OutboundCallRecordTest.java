package com.bookit.servicecalllogging.logging;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OutboundCallRecordTest {

    @Test
    void constructorSetsEverySevenFieldAndAccessorsReturnThem() {
        Instant timestamp = Instant.parse("2026-08-27T10:15:30Z");

        OutboundCallRecord record = new OutboundCallRecord(
                "my-service",
                "payments-service:8080",
                "POST",
                200,
                "2xx",
                0,
                timestamp);

        assertThat(record.source()).isEqualTo("my-service");
        assertThat(record.destination()).isEqualTo("payments-service:8080");
        assertThat(record.httpMethod()).isEqualTo("POST");
        assertThat(record.httpStatusCode()).isEqualTo(200);
        assertThat(record.httpStatusGroup()).isEqualTo("2xx");
        assertThat(record.responseCode()).isEqualTo(0);
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
                Instant.now());

        assertThat(record.httpStatusCode()).isNull();
        assertThat(record.responseCode()).isNull();
        assertThat(record.timestamp()).isNotNull();
        assertThat(record.httpStatusGroup()).isEqualTo("network-error");
    }
}
