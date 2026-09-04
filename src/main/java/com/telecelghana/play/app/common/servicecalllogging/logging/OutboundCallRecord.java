package com.telecelghana.play.app.common.servicecalllogging.logging;

import java.time.Instant;

/**
 * One intercepted outbound HTTP call, assembled by the instrumentation path and handed to
 * {@link CallLogger}. Never persisted or serialised.
 *
 * <p>This type is the exhaustive list of what the starter is permitted to log
 * (Constitution Principle VII — Data Hygiene, as amended in constitution v1.1.0 to admit the
 * business message). No credential, cookie, header-bag, or body field may ever be added here.
 *
 * @param source          the calling service name ({@code spring.application.name}, or
 *                        {@code "unknown"}); never null
 * @param destination     the resolved destination service name; never null
 * @param httpMethod      the outgoing request method, e.g. {@code "GET"}; never null
 * @param httpStatusCode  the received HTTP status, or null when no response was received
 * @param httpStatusGroup the status classification: {@code 2xx}/{@code 4xx}/{@code 5xx}/
 *                        {@code network-error} (also {@code 1xx}/{@code 3xx}); never null
 * @param responseCode    the parsed business code, or null when it could not be determined
 * @param message         the business outcome message read from the matched envelope
 *                        combination, or null when it could not be determined; independent of
 *                        {@code responseCode}
 * @param destinationUri  the <strong>path component</strong> of the outbound call's destination —
 *                        templated form when it could be determined, else the raw path, else the
 *                        literal {@code "unknown"}. Never null and never empty, so a log line's
 *                        field count is constant. Never carries a scheme, host, port, userinfo
 *                        component or query string, which is what makes the no-credential
 *                        guarantee structural rather than a redaction step
 * @param inboundUri      the <strong>path component</strong> of the inbound request this service
 *                        was handling when it made the call, else the literal {@code "unknown"};
 *                        same never-null, path-only guarantees as {@code destinationUri}
 * @param operation       the caller-supplied business operation name, else the literal
 *                        {@code "undefined"}; bounded in length and character set, never null
 * @param timestamp       when the call was initiated; never null
 */
public record OutboundCallRecord(
        String source,
        String destination,
        String httpMethod,
        Integer httpStatusCode,
        String httpStatusGroup,
        Integer responseCode,
        String message,
        String destinationUri,
        String inboundUri,
        String operation,
        Instant timestamp) {

    /** Documented fallback for a URI dimension that could not be determined at all. */
    public static final String UNKNOWN_URI = "unknown";

    /** Documented fallback for an operation the caller did not supply, or that was rejected. */
    public static final String UNDEFINED_OPERATION = "undefined";

    /**
     * Normalises the three fields added by spec 003 to their documented fallbacks.
     *
     * <p>The invariant these uphold is that the fields are never null and never empty, so a
     * dimension is never present for some calls and absent for others (FR-009, FR-019, SC-004).
     * Normalising here rather than at each call site means the guarantee holds however the record
     * is constructed — including from a consumer's own code.
     */
    public OutboundCallRecord {
        destinationUri = blankTo(destinationUri, UNKNOWN_URI);
        inboundUri = blankTo(inboundUri, UNKNOWN_URI);
        operation = blankTo(operation, UNDEFINED_OPERATION);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
