package com.bookit.servicecalllogging.logging;

import java.time.Instant;

/**
 * One intercepted outbound HTTP call, assembled by the instrumentation path and handed to
 * {@link CallLogger}. Never persisted or serialised.
 *
 * <p>This type is the exhaustive list of what the starter is permitted to log
 * (Constitution Principle VII — Data Hygiene). No credential, cookie, header-bag, or body
 * field may ever be added here.
 *
 * @param source          the calling service name ({@code spring.application.name}, or
 *                        {@code "unknown"}); never null
 * @param destination     the resolved destination service name; never null
 * @param httpMethod      the outgoing request method, e.g. {@code "GET"}; never null
 * @param httpStatusCode  the received HTTP status, or null when no response was received
 * @param httpStatusGroup the status classification: {@code 2xx}/{@code 4xx}/{@code 5xx}/
 *                        {@code network-error} (also {@code 1xx}/{@code 3xx}); never null
 * @param responseCode    the parsed business code, or null when it could not be determined
 * @param timestamp       when the call was initiated; never null
 */
public record OutboundCallRecord(
        String source,
        String destination,
        String httpMethod,
        Integer httpStatusCode,
        String httpStatusGroup,
        Integer responseCode,
        Instant timestamp) {
}
