package com.telecelghana.play.app.common.servicecalllogging;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * All thirteen configuration keys the starter exposes, bound from the
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
        requireNotBlank(sourceHeaderName, "service-call-logging.source-header-name");
        requireNotBlank(destinationHeaderName, "service-call-logging.destination-header-name");
        requireNotBlank(serviceNameHintHeader, "service-call-logging.service-name-hint-header");
        if (maxBodyBytes < 0) {
            throw new IllegalArgumentException(
                    "service-call-logging.max-body-bytes must not be negative, but was " + maxBodyBytes);
        }
    }

    /**
     * Enforces a {@code @NotBlank} constraint without a Bean Validation provider.
     *
     * <p>Constitution Principle II: this starter must not change a consuming service's startup
     * success. Spring's configuration-properties binder decides whether to run JSR-303
     * validation from the presence of {@code jakarta.validation.Validator} alone and never
     * checks for an implementation, so a single {@code @Validated} on this class made every
     * application that carries the API without a provider fail to start. The constraint
     * annotations below are retained as documentation and for the configuration-metadata
     * processor; enforcement lives here, where no provider is required.
     */
    private static void requireNotBlank(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
    }

    /**
     * Micrometer naming configuration. The counter is registered as {@code <prefix>.total} and
     * the latency timer as {@code <prefix>.latency}; Prometheus renders those as
     * {@code http_outbound_calls_total} and {@code http_outbound_calls_latency_seconds}.
     *
     * @param prefix                counter and timer name prefix
     * @param destinationTagName    tag key for the destination service name
     * @param outcomeTagName        tag key for the call outcome
     * @param statusGroupTagName    tag key for the HTTP status group
     * @param destinationUriTagName tag key for the destination URI path (spec 003)
     * @param inboundUriTagName     tag key for the inbound request URI path (spec 003)
     * @param operationTagName      tag key for the caller-supplied business operation (spec 003)
     * @param latencyBuckets        explicit latency bucket boundaries for the timer, as
     *                              configured strings ({@code 50ms}, {@code 1s}, {@code PT2S});
     *                              empty means delegate to the metrics library's own default
     *                              distribution (spec 003 FR-012). Bound as strings, not as
     *                              {@code Duration}, deliberately: FR-014 requires an unusable
     *                              value to be discarded rather than to fail the consuming
     *                              service's startup, and a typed binding would reject it before
     *                              this starter ever saw it. Use
     *                              {@link Metrics#latencyBucketDurations()} for the parsed,
     *                              filtered boundaries. The effective default is a property of
     *                              the library version on the classpath, not of this starter —
     *                              see the README.
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
            String statusGroupTagName,

            @DefaultValue("destination_uri")
            @NotBlank
            String destinationUriTagName,

            @DefaultValue("inbound_uri")
            @NotBlank
            String inboundUriTagName,

            @DefaultValue("operation")
            @NotBlank
            String operationTagName,

            List<String> latencyBuckets) {

        /**
         * Same provider-free enforcement as the outer record — see {@code requireNotBlank} —
         * plus normalisation of an absent bucket list to an empty one, so every caller sees the
         * same shape and "empty" unambiguously means "delegate to the library" (FR-014).
         */
        public Metrics {
            requireNotBlank(prefix, "service-call-logging.metrics.prefix");
            requireNotBlank(destinationTagName, "service-call-logging.metrics.destination-tag-name");
            requireNotBlank(outcomeTagName, "service-call-logging.metrics.outcome-tag-name");
            requireNotBlank(statusGroupTagName, "service-call-logging.metrics.status-group-tag-name");
            requireNotBlank(destinationUriTagName, "service-call-logging.metrics.destination-uri-tag-name");
            requireNotBlank(inboundUriTagName, "service-call-logging.metrics.inbound-uri-tag-name");
            requireNotBlank(operationTagName, "service-call-logging.metrics.operation-tag-name");
            latencyBuckets = latencyBuckets == null ? List.of() : List.copyOf(latencyBuckets);
        }

        /**
         * The configured boundaries, parsed and filtered down to the ones actually usable.
         *
         * <p>Anything unparseable, zero or negative is discarded rather than rejected (FR-014).
         * An empty result — whether because nothing was configured or because nothing survived
         * filtering — means "delegate to the metrics library's default distribution" (FR-012).
         */
        public List<Duration> latencyBucketDurations() {
            List<Duration> parsed = new java.util.ArrayList<>();
            for (String raw : this.latencyBuckets) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                try {
                    Duration candidate = DurationStyle.detectAndParse(raw.trim());
                    if (!candidate.isZero() && !candidate.isNegative()) {
                        parsed.add(candidate);
                    }
                } catch (RuntimeException unusable) {
                    // Discarded on purpose: a bad boundary costs a bucket, never a startup.
                }
            }
            return List.copyOf(parsed);
        }
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
