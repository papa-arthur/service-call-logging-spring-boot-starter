package com.bookit.servicecalllogging;

/**
 * The outcome of matching one response body against the consuming service's configured envelope
 * combinations (see {@code contracts/envelope-matching.md}).
 *
 * <p>Deliberately free of any Jackson or {@code spring-web} type: the instrumentation path holds
 * this record and the {@link EnvelopeFieldExtractor} interface even in a consumer with no JSON
 * library on the classpath (Constitution Principle II — Zero Forced Footprint).
 *
 * @param rawCode         the numeric code read from the matched combination's code field, or
 *                        null when no combination matched — including the built-in default
 * @param successfulValue the matched combination's successful value, or the built-in default
 *                        ({@code 0}) when nothing matched. Always meaningful: it classifies the
 *                        call's code even when that code came from a consumer's own
 *                        {@link ResponseCodeExtractor} rather than from this match.
 * @param message         the string read from the matched combination's message field, or null
 *                        when absent, non-string, or nothing matched. Independent of
 *                        {@code rawCode}: either may be present while the other is not.
 */
public record EnvelopeMatch(Integer rawCode, int successfulValue, String message) {

    /** Nothing matched, and nothing could be read: the shape every failure degrades to. */
    public static final EnvelopeMatch NONE =
            new EnvelopeMatch(null, ServiceCallLoggingProperties.Envelope.DEFAULT_SUCCESSFUL_VALUE, null);
}
