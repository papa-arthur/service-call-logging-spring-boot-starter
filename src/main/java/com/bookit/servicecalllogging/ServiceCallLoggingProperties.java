package com.bookit.servicecalllogging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * All nine configuration keys the starter exposes, bound from the
 * {@code service-call-logging.*} prefix. Fully documented in
 * {@code contracts/configuration.md} and in the project README.
 *
 * <p>Constructor-bound and immutable. The validation annotations are only enforced when a
 * Jakarta Validation provider is on the consumer's classpath; without one, binding proceeds
 * unvalidated rather than failing (Constitution Principle II — Zero Forced Footprint).
 *
 * @param enabled               master switch; {@code false} registers no beans at all
 * @param sourceHeaderName      request header stamped with the calling service name
 * @param destinationHeaderName request header stamped with the destination service name
 * @param serviceNameHintHeader header read off the outgoing request to override destination
 *                              name resolution
 * @param maxBodyBytes          cap on bytes read for {@code responseCode} extraction;
 *                              {@code 0} skips body reading entirely
 * @param metrics               Micrometer naming configuration
 */
@ConfigurationProperties(prefix = "service-call-logging")
@Validated
public record ServiceCallLoggingProperties(

        @DefaultValue("true")
        boolean enabled,

        @DefaultValue("X-Source-Service")
        @NotBlank
        String sourceHeaderName,

        @DefaultValue("X-Destination-Service")
        @NotBlank
        String destinationHeaderName,

        @DefaultValue("service_name")
        @NotBlank
        String serviceNameHintHeader,

        @DefaultValue("1048576")
        @Min(0)
        int maxBodyBytes,

        @DefaultValue
        @Valid
        Metrics metrics) {

    /**
     * Micrometer naming configuration. The counter is registered as {@code <prefix>.total};
     * Prometheus renders that as {@code http_outbound_calls_total}.
     *
     * @param prefix             counter name prefix
     * @param destinationTagName tag key for the destination service name
     * @param outcomeTagName     tag key for the call outcome
     * @param statusGroupTagName tag key for the HTTP status group
     */
    public record Metrics(

            @DefaultValue("http.outbound.calls")
            @NotBlank
            String prefix,

            @DefaultValue("destination")
            @NotBlank
            String destinationTagName,

            @DefaultValue("outcome")
            @NotBlank
            String outcomeTagName,

            @DefaultValue("http_status_group")
            @NotBlank
            String statusGroupTagName) {
    }
}
