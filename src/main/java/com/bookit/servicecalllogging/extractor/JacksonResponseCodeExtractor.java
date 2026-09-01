package com.bookit.servicecalllogging.extractor;

import com.bookit.servicecalllogging.EnvelopeFieldExtractor;
import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * Default {@link ResponseCodeExtractor}: reads the business outcome code from a JSON response
 * body using the consuming service's configured envelope combinations.
 *
 * <p>Since spec 002 this is a thin adapter over {@link JacksonEnvelopeFieldExtractor}, which owns
 * the matching rule ({@code contracts/envelope-matching.md}). With nothing configured, the
 * effective behaviour is exactly what it always was: the top-level {@code responseCode} integer.
 *
 * <p>The {@link ResponseCodeExtractor} interface itself is unchanged, so every consumer-supplied
 * implementation keeps compiling and behaving identically (Constitution Principle IV).
 *
 * <p>This is a plain class — it carries no conditional annotations of its own. The
 * {@code @ConditionalOnClass} guard for Jackson and the {@code @ConditionalOnMissingBean} guard
 * that lets a consumer replace it live on the {@code @Bean} factory method in
 * {@code ServiceCallLoggingAutoConfiguration}, so this class is never loaded when Jackson is
 * absent from the consumer's classpath.
 *
 * <p>Honours the SPI contract exhaustively: no input causes an exception to escape.
 */
public class JacksonResponseCodeExtractor implements ResponseCodeExtractor {

    private final EnvelopeFieldExtractor envelopeFieldExtractor;

    /** Uses the built-in default combination only. */
    public JacksonResponseCodeExtractor() {
        this(new JacksonEnvelopeFieldExtractor(List.of()));
    }

    /**
     * @param objectMapper the mapper to parse with; typically the consumer's own context mapper
     */
    public JacksonResponseCodeExtractor(ObjectMapper objectMapper) {
        this(new JacksonEnvelopeFieldExtractor(List.of(), objectMapper));
    }

    /**
     * @param envelopeFieldExtractor the matching engine to read the code through; when null, the
     *                               built-in default combination is used
     */
    public JacksonResponseCodeExtractor(EnvelopeFieldExtractor envelopeFieldExtractor) {
        this.envelopeFieldExtractor = envelopeFieldExtractor == null
                ? new JacksonEnvelopeFieldExtractor(List.of())
                : envelopeFieldExtractor;
    }

    @Override
    public Optional<Integer> extract(byte[] bodyBytes) {
        try {
            return Optional.ofNullable(this.envelopeFieldExtractor.extract(bodyBytes).rawCode());
        } catch (Exception ex) {
            // Contract rule 1: never throw. Any unparseable input degrades to "absent".
            return Optional.empty();
        }
    }
}
