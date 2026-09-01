package com.bookit.servicecalllogging.metrics;

import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Records the outbound-call counter that Grafana dashboards and alerts are built on
 * (FR-014 to FR-019).
 *
 * <p>One monotonically increasing counter, {@code <prefix>.total}, tagged with
 * {@code destination}, {@code outcome} and {@code http_status_group}. Prometheus renders the
 * default as {@code http_outbound_calls_total}. Counter registration is idempotent: Micrometer
 * returns the existing counter for a name and tag-set it has already seen, so a destination's
 * series accumulates across calls rather than being recreated.
 *
 * <p>Counters alone are enough for a monitoring system to derive success and failure
 * <em>rates</em> over any window via its own rate functions (FR-017) — the starter deliberately
 * computes no rates itself.
 *
 * <p>This bean exists only when the consumer already has a {@link MeterRegistry}, so Actuator
 * is never a forced dependency (Constitution Principle II).
 */
public class OutboundCallMetrics {

    private final MeterRegistry meterRegistry;
    private final ServiceCallLoggingProperties.Metrics config;
    private final String counterName;

    public OutboundCallMetrics(MeterRegistry meterRegistry, ServiceCallLoggingProperties properties) {
        this.meterRegistry = meterRegistry;
        this.config = properties.metrics();
        this.counterName = this.config.prefix() + ".total";
    }

    /**
     * Increments the counter for one completed (or failed) outbound call.
     *
     * <p>Callers wrap this, but it is defensive in its own right: a null tag value would make
     * Micrometer throw, and a metrics failure must never reach a business call.
     *
     * @param destination     the resolved destination service name
     * @param outcome         the interpreted {@code responseCode} outcome
     * @param httpStatusGroup the HTTP status classification
     */
    public void record(String destination, Outcome outcome, String httpStatusGroup) {
        Counter.builder(this.counterName)
                .description("Outbound HTTP call counter, by destination, outcome and HTTP status group")
                .tag(this.config.destinationTagName(), orUnknown(destination))
                .tag(this.config.outcomeTagName(), outcome == null ? Outcome.ABSENT.label() : outcome.label())
                .tag(this.config.statusGroupTagName(), orUnknown(httpStatusGroup))
                .register(this.meterRegistry)
                .increment();
    }

    private static String orUnknown(String tagValue) {
        return tagValue == null ? "unknown" : tagValue;
    }
}
