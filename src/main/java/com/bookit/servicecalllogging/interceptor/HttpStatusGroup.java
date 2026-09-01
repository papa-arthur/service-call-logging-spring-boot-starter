package com.bookit.servicecalllogging.interceptor;

/**
 * Classifies an HTTP status code into the coarse group published as the
 * {@code http_status_group} metric tag (FR-016).
 *
 * <p>The documented values are {@code 2xx}, {@code 4xx}, {@code 5xx} and
 * {@code network-error}. {@code 1xx} and {@code 3xx} are also emitted verbatim when they
 * actually occur: a response <em>was</em> received, so labelling it {@code network-error}
 * would be factually wrong, and folding it into {@code 2xx} would be worse. Queries filtering
 * on the four documented values are unaffected.
 */
public final class HttpStatusGroup {

    /** No HTTP response was received at all: connection refused, timeout, DNS failure, etc. */
    public static final String NETWORK_ERROR = "network-error";

    private HttpStatusGroup() {
    }

    /**
     * @param statusCode the received HTTP status code
     * @return the group label; never null
     */
    public static String classify(int statusCode) {
        int hundreds = statusCode / 100;
        return switch (hundreds) {
            case 1 -> "1xx";
            case 2 -> "2xx";
            case 3 -> "3xx";
            case 4 -> "4xx";
            case 5 -> "5xx";
            default -> NETWORK_ERROR;
        };
    }
}
