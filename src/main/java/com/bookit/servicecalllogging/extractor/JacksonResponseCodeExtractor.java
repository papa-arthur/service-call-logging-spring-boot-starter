package com.bookit.servicecalllogging.extractor;

import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Default {@link ResponseCodeExtractor}: reads a top-level integer {@code responseCode} field
 * from a JSON response body.
 *
 * <p>This is a plain class — it carries no conditional annotations of its own. The
 * {@code @ConditionalOnClass} guard for Jackson and the {@code @ConditionalOnMissingBean}
 * guard that lets a consumer replace it live on the {@code @Bean} factory method in
 * {@code ServiceCallLoggingAutoConfiguration}, so this class is never loaded when Jackson is
 * absent from the consumer's classpath.
 *
 * <p>Honours the SPI contract exhaustively: no input causes an exception to escape.
 */
public class JacksonResponseCodeExtractor implements ResponseCodeExtractor {

    private static final String RESPONSE_CODE_FIELD = "responseCode";

    private final ObjectMapper objectMapper;

    public JacksonResponseCodeExtractor() {
        this(new ObjectMapper());
    }

    /**
     * @param objectMapper the mapper to parse with; typically the consumer's own context mapper
     */
    public JacksonResponseCodeExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Integer> extract(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return Optional.empty();
        }
        try {
            JsonNode root = this.objectMapper.readTree(bodyBytes);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            JsonNode responseCode = root.get(RESPONSE_CODE_FIELD);
            if (responseCode == null || !responseCode.isInt()) {
                return Optional.empty();
            }
            return Optional.of(responseCode.intValue());
        } catch (Exception ex) {
            // Contract rule 1: never throw. Any unparseable input degrades to "absent".
            return Optional.empty();
        }
    }
}
