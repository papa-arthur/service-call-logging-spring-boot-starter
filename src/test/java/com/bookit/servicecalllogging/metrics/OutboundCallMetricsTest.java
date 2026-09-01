package com.bookit.servicecalllogging.metrics;

import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import com.bookit.servicecalllogging.testsupport.TestProperties;
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
                new ServiceCallLoggingProperties.Metrics("custom.calls", "target", "result", "status_bucket"));

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
}
