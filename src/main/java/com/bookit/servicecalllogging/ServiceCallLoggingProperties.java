package com.bookit.servicecalllogging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

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
 * @param envelopes             ordered response-envelope field-name combinations; empty means
 *                              "use the built-in default for every call"
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
        Metrics metrics,

        List<Envelope> envelopes) {

    /**
     * Normalises an absent {@code envelopes} list to an empty one, so every caller — the binder,
     * a test, or a consumer constructing this record directly — sees the same shape.
     */
    public ServiceCallLoggingProperties {
        envelopes = envelopes == null ? List.of() : List.copyOf(envelopes);
    }

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

    /**
     * One response-envelope combination: the pair of field names to read, plus the single code
     * value that means success. Combinations are matched against a response body in configured
     * order and are not bound to any destination — see
     * {@code contracts/envelope-matching.md}.
     *
     * <p>Each field falls back independently to its documented default, so configuring only
     * {@code code-field} still reads the message from {@code message} (FR-006). The fallback is
     * applied in the compact constructor rather than left to the binder, so it holds for a
     * programmatically-constructed instance too.
     *
     * @param codeField       name of the top-level field holding the numeric outcome code
     * @param messageField    name of the top-level field holding the human-readable message
     * @param successfulValue the one code value that means success; every other numeric value
     *                        the field can hold is unsuccessful
     */
    public record Envelope(

            @DefaultValue("responseCode")
            String codeField,

            @DefaultValue("message")
            String messageField,

            @DefaultValue("0")
            int successfulValue) {

        /** The field name the starter has always read, and still reads by default. */
        public static final String DEFAULT_CODE_FIELD = "responseCode";

        /** The message field name introduced with configurable envelopes. */
        public static final String DEFAULT_MESSAGE_FIELD = "message";

        /** The code value the starter has always treated as success. */
        public static final int DEFAULT_SUCCESSFUL_VALUE = 0;

        /** The built-in combination, always tried last when nothing configured matches. */
        public static final Envelope DEFAULT =
                new Envelope(DEFAULT_CODE_FIELD, DEFAULT_MESSAGE_FIELD, DEFAULT_SUCCESSFUL_VALUE);

        public Envelope {
            codeField = isBlank(codeField) ? DEFAULT_CODE_FIELD : codeField;
            messageField = isBlank(messageField) ? DEFAULT_MESSAGE_FIELD : messageField;
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
