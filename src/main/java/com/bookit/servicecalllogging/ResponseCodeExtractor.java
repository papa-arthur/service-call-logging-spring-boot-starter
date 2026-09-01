package com.bookit.servicecalllogging;

import java.util.Optional;

/**
 * Consumer-replaceable strategy for reading the business {@code responseCode} out of a raw
 * response body.
 *
 * <p>Register a bean of this type in any {@code @Configuration} class to replace the
 * starter's default Jackson-based implementation — the default is declared
 * {@code @ConditionalOnMissingBean(ResponseCodeExtractor.class)}, so a consumer-supplied bean
 * takes precedence automatically with no other configuration change.
 *
 * <p><strong>Every implementation MUST satisfy the following contract</strong>
 * (see {@code contracts/response-code-extractor-spi.md}):
 * <ol>
 *   <li><strong>Never throw.</strong> Return {@link Optional#empty()} for any input that
 *       cannot be parsed. An exception escaping this method would reach the instrumentation
 *       path on every call.</li>
 *   <li><strong>Never return null.</strong> Use {@link Optional#empty()} to signal absence.</li>
 *   <li><strong>Be pure.</strong> No I/O, no logging, no shared-state mutation.</li>
 *   <li><strong>Return in bounded time.</strong> No blocking calls, no locks.</li>
 *   <li><strong>Treat {@code bodyBytes} as read-only.</strong> The array is shared.</li>
 * </ol>
 */
@FunctionalInterface
public interface ResponseCodeExtractor {

    /**
     * Extracts the integer {@code responseCode} from the raw response body bytes.
     *
     * @param bodyBytes the raw response body, at most {@code max-body-bytes} in length; may be
     *                  empty, and implementations must tolerate null defensively
     * @return the extracted code, or {@link Optional#empty()} if it could not be determined
     */
    Optional<Integer> extract(byte[] bodyBytes);
}
