package com.telecelghana.play.app.common.servicecalllogging.metrics;

import com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.TestProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class OutboundCallMetricsTest {

    private MeterRegistry registry;
    private OutboundCallMetrics metrics;

    @BeforeEach
    void setUp() {
        this.registry = new SimpleMeterRegistry();
        this.metrics = new OutboundCallMetrics(this.registry, TestProperties.defaults());
    }

    private double counterValue(String destination, String outcome, String statusGroup) {
        Counter counter = this.registry.find("http.outbound.calls.total")
                .tag("destination", destination)
                .tag("outcome", outcome)
                .tag("http_status_group", statusGroup)
                .counter();
        return counter == null ? -1 : counter.count();
    }

    @Test
    void recordingASuccessIncrementsTheFullyTaggedCounter() {
        this.metrics.record("svc:8080", Outcome.SUCCESS, "2xx");

        assertThat(counterValue("svc:8080", "success", "2xx")).isEqualTo(1.0);
    }

    @Test
    void repeatedRecordsAccumulateOnTheSameCounter() {
        this.metrics.record("svc:8080", Outcome.SUCCESS, "2xx");
        this.metrics.record("svc:8080", Outcome.SUCCESS, "2xx");
        this.metrics.record("svc:8080", Outcome.SUCCESS, "2xx");

        assertThat(counterValue("svc:8080", "success", "2xx")).isEqualTo(3.0);
        assertThat(this.registry.find("http.outbound.calls.total").counters()).hasSize(1);
    }

    @Test
    void distinctDestinationsAreTrackedIndependently() {
        this.metrics.record("dest-a:8080", Outcome.SUCCESS, "2xx");
        this.metrics.record("dest-a:8080", Outcome.SUCCESS, "2xx");
        this.metrics.record("dest-b:9090", Outcome.SUCCESS, "2xx");

        assertThat(counterValue("dest-a:8080", "success", "2xx")).isEqualTo(2.0);
        assertThat(counterValue("dest-b:9090", "success", "2xx")).isEqualTo(1.0);
    }

    @Test
    void everyOutcomeAndStatusGroupCombinationGetsItsOwnTimeSeries() {
        this.metrics.record("svc:8080", Outcome.SUCCESS, "2xx");
        this.metrics.record("svc:8080", Outcome.FAILURE, "2xx");
        this.metrics.record("svc:8080", Outcome.ABSENT, "5xx");
        this.metrics.record("svc:8080", Outcome.ABSENT, "network-error");

        assertThat(counterValue("svc:8080", "success", "2xx")).isEqualTo(1.0);
        assertThat(counterValue("svc:8080", "failure", "2xx")).isEqualTo(1.0);
        assertThat(counterValue("svc:8080", "absent", "5xx")).isEqualTo(1.0);
        assertThat(counterValue("svc:8080", "absent", "network-error")).isEqualTo(1.0);
        assertThat(this.registry.find("http.outbound.calls.total").counters()).hasSize(4);
    }

    @Test
    void configuredPrefixAndTagNamesAreHonoured() {
        ServiceCallLoggingProperties custom = new ServiceCallLoggingProperties(
                true, "X-Source-Service", "X-Destination-Service", "service_name", 1_048_576,
                new ServiceCallLoggingProperties.Metrics("custom.calls", "target", "result", "status_bucket",
                        "target_uri", "caller_uri", "biz_op", java.util.List.of()),
                java.util.List.of());

        new OutboundCallMetrics(this.registry, custom).record("svc:8080", Outcome.FAILURE, "4xx");

        Counter counter = this.registry.find("custom.calls.total")
                .tag("target", "svc:8080")
                .tag("result", "failure")
                .tag("status_bucket", "4xx")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordingNeverThrowsEvenForOddInputs() {
        assertThatCode(() -> {
            this.metrics.record(null, Outcome.SUCCESS, "2xx");
            this.metrics.record("svc:8080", Outcome.SUCCESS, null);
        }).doesNotThrowAnyException();
    }

    @Test
    void theCounterCarriesExactlyTheDeclaredTagsAndNeverOneDerivedFromTheMessage() {
        this.metrics.record("svc:8080", Outcome.SUCCESS, "2xx");

        Counter counter = this.registry.find("http.outbound.calls.total").counter();
        assertThat(counter).isNotNull();

        // research.md §5: the extracted message is a logged field ONLY. Using it as a tag value
        // would be an unbounded-cardinality hazard. The tag set is therefore closed — it grew by
        // the two URI dimensions and the operation in spec 003 (FR-028), and by nothing else.
        assertThat(counter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactlyInAnyOrder("destination", "outcome", "http_status_group",
                        "destination_uri", "inbound_uri", "operation");
    }

    @Test
    void noTagValueIsEverDerivedFromTheBusinessMessage() {
        // The guarantee the previous test's tag-count was really protecting, asserted directly so
        // it survives every future dimension the tag set legitimately gains.
        this.metrics.record("svc:8080", Outcome.FAILURE, "4xx");

        Counter counter = this.registry.find("http.outbound.calls.total").counter();
        assertThat(counter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .as("no tag key may reference the message")
                .noneMatch(key -> key.toLowerCase().contains("message"));
    }

    @Test
    void theCounterCarriesTheMetricSurfaceUriValuesNotTheRawPath() {
        // FR-004 — an untemplatable URI must contribute the placeholder here, never a raw path.
        this.metrics.record("svc:8080", Outcome.SUCCESS, "2xx", "unresolved", "/api/v1/pay", 1_000L);

        Counter counter = this.registry.find("http.outbound.calls.total")
                .tag("destination_uri", "unresolved")
                .tag("inbound_uri", "/api/v1/pay")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void absentUriValuesFallBackToUnknownRatherThanThrowing() {
        this.metrics.record("svc:8080", Outcome.SUCCESS, "2xx", null, null, 1_000L);

        assertThat(this.registry.find("http.outbound.calls.total")
                .tag("destination_uri", "unknown")
                .tag("inbound_uri", "unknown")
                .counter()).isNotNull();
    }

    @Test
    void theOperationTagDefaultsToUndefinedRatherThanBeingOmitted() {
        // FR-023 — the dimension is never sometimes-present and sometimes-absent for the same
        // metric, so a dashboard never sees it appear and disappear.
        this.metrics.record("svc:8080", Outcome.SUCCESS, "2xx");

        assertThat(this.registry.find("http.outbound.calls.total")
                .tag("operation", "undefined").counter()).isNotNull();
    }

    @Test
    void aSuppliedOperationBecomesTheTagValue() {
        this.metrics.record("svc:8080", Outcome.SUCCESS, "2xx",
                "/accounts/{id}", "/api/v1/pay", "SendMoney", 1_000L);

        assertThat(this.registry.find("http.outbound.calls.total")
                .tag("operation", "SendMoney").counter()).isNotNull();
    }
}
