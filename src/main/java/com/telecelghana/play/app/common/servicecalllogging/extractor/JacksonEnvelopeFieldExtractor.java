package com.telecelghana.play.app.common.servicecalllogging.extractor;

import com.telecelghana.play.app.common.servicecalllogging.EnvelopeFieldExtractor;
import com.telecelghana.play.app.common.servicecalllogging.EnvelopeMatch;
import com.telecelghana.play.app.common.servicecalllogging.ServiceCallLoggingProperties.Envelope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Default {@link EnvelopeFieldExtractor}: matches a JSON response body against the consuming
 * service's ordered envelope combinations, exactly as specified in
 * {@code contracts/envelope-matching.md}.
 *
 * <p>The rule, in one sentence: walk the configured combinations in order, then the built-in
 * default, and use the first whose code field is present with an integer value.
 *
 * <p>Two properties of that rule matter enough to restate:
 * <ul>
 *   <li><strong>A combination's message field never affects whether it matches.</strong> An API
 *       that omits its message on success must not fall through to the wrong combination.</li>
 *   <li><strong>Order is significant.</strong> When a body could satisfy two combinations, the
 *       earlier one wins — deterministically, and it is the adopter's job to order them.</li>
 * </ul>
 *
 * <p>Like the default {@code ResponseCodeExtractor}, this class carries no conditional
 * annotations of its own; the {@code @ConditionalOnClass} guard for Jackson lives on the
 * {@code @Bean} factory method, so it is never loaded when Jackson is absent.
 */
public class JacksonEnvelopeFieldExtractor implements EnvelopeFieldExtractor {

    /** The configured combinations, with the built-in default always appended last. */
    private final List<Envelope> envelopes;

    private final ObjectMapper objectMapper;

    public JacksonEnvelopeFieldExtractor(List<Envelope> configuredEnvelopes) {
        this(configuredEnvelopes, new ObjectMapper());
    }

    /**
     * @param configuredEnvelopes the consuming service's list, in configured order; may be null,
     *                            empty, or contain null entries — all are tolerated
     * @param objectMapper        the mapper to parse with; typically the consumer's own
     */
    public JacksonEnvelopeFieldExtractor(List<Envelope> configuredEnvelopes, ObjectMapper objectMapper) {
        List<Envelope> effective = new ArrayList<>();
        if (configuredEnvelopes != null) {
            for (Envelope envelope : configuredEnvelopes) {
                if (envelope != null) {
                    effective.add(envelope);
                }
            }
        }
        // The built-in default is simply one more combination, always tried last (FR-005).
        effective.add(Envelope.DEFAULT);
        this.envelopes = List.copyOf(effective);
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public EnvelopeMatch extract(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return EnvelopeMatch.NONE;
        }
        try {
            JsonNode root = this.objectMapper.readTree(bodyBytes);
            if (root == null || !root.isObject()) {
                return EnvelopeMatch.NONE;
            }
            for (Envelope envelope : this.envelopes) {
                JsonNode code = root.get(envelope.codeField());
                if (code != null && code.isInt()) {
                    return new EnvelopeMatch(code.intValue(), envelope.successfulValue(),
                            messageFrom(root, envelope));
                }
            }
            // Nothing matched: the code is absent, but a message may still be readable under the
            // built-in default's field name, so the two stay independent (FR-011/FR-012).
            return new EnvelopeMatch(null, Envelope.DEFAULT.successfulValue(),
                    messageFrom(root, Envelope.DEFAULT));
        } catch (Exception ex) {
            // Constitution Principle I: never throw. Anything unparseable degrades to no match.
            return EnvelopeMatch.NONE;
        }
    }

    private static String messageFrom(JsonNode root, Envelope envelope) {
        JsonNode message = root.get(envelope.messageField());
        return message != null && message.isTextual() ? message.textValue() : null;
    }
}
