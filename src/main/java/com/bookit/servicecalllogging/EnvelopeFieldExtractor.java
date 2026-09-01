package com.bookit.servicecalllogging;

/**
 * Reads the business outcome code and its accompanying message out of a raw response body, using
 * the consuming service's configured envelope combinations.
 *
 * <p>This runs on every instrumented call <strong>independently of</strong>
 * {@link ResponseCodeExtractor}: a consumer who has replaced code extraction with their own bean
 * still gets a message, and still has their code classified against the matched combination's
 * successful value (see {@code contracts/response-code-extractor-spi.md}).
 *
 * <p>Declared as an interface purely so the instrumentation path can hold it without resolving
 * Jackson — the default implementation, {@code JacksonEnvelopeFieldExtractor}, is only loaded
 * when Jackson is on the consumer's classpath (Constitution Principle II). Like every other bean
 * the starter contributes, it is replaceable: declare your own and it takes precedence.
 *
 * <p><strong>Implementations MUST never throw</strong> — return {@link EnvelopeMatch#NONE} for
 * anything that cannot be interpreted. An exception escaping here would reach the instrumentation
 * path on every call (Constitution Principle I).
 */
@FunctionalInterface
public interface EnvelopeFieldExtractor {

    /**
     * @param bodyBytes the raw response body, at most {@code max-body-bytes} long; may be null
     *                  or empty, and implementations must tolerate both
     * @return the match; never null — use {@link EnvelopeMatch#NONE} to signal "nothing matched"
     */
    EnvelopeMatch extract(byte[] bodyBytes);
}
