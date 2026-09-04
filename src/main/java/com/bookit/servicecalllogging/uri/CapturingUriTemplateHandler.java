package com.bookit.servicecalllogging.uri;

import org.springframework.web.util.UriTemplateHandler;

import java.net.URI;
import java.util.Map;

/**
 * Wraps a {@code RestTemplate}'s {@link UriTemplateHandler} to record the template it expands,
 * so the interceptor can report the templated form rather than a per-request-unique raw path.
 *
 * <p>Without this, every blocking-path call would report the {@code unresolved} placeholder in its
 * destination-URI metric tag, making that dimension a constant on the starter's primary client and
 * defeating the feature's headline outcome (research.md §1, plan.md Complexity Tracking).
 *
 * <p>Constitution Principle I: expansion is the business call's own work, so a capture failure must
 * be invisible to it. The delegate is called first and its result returned unconditionally; the
 * capture runs afterwards inside a guard and can only cost telemetry.
 */
public class CapturingUriTemplateHandler implements UriTemplateHandler {

    private final UriTemplateHandler delegate;

    public CapturingUriTemplateHandler(UriTemplateHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public URI expand(String uriTemplate, Map<String, ?> uriVariables) {
        URI expanded = this.delegate.expand(uriTemplate, uriVariables);
        safeCapture(uriTemplate, expanded);
        return expanded;
    }

    @Override
    public URI expand(String uriTemplate, Object... uriVariables) {
        URI expanded = this.delegate.expand(uriTemplate, uriVariables);
        safeCapture(uriTemplate, expanded);
        return expanded;
    }

    /** The delegate's result has already been computed; nothing here may disturb it. */
    private void safeCapture(String uriTemplate, URI expanded) {
        try {
            UriTemplateCapture.capture(uriTemplate, expanded);
        } catch (Exception captureFailure) {
            // Telemetry only. The expanded URI is returned regardless.
        }
    }
}
