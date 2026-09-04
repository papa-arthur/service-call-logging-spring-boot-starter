package com.telecelghana.play.app.common.servicecalllogging.uri;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Resolves the path of the inbound request this service is handling, so an outbound call can be
 * attributed to the endpoint that triggered it.
 *
 * <p><strong>Deliberately reads the attribute key as a literal string.</strong> The constant lives
 * on {@code org.springframework.web.servlet.HandlerMapping}, which is in spring-webmvc, and the
 * typed accessor would be {@code ServletRequestAttributes}, which needs jakarta.servlet-api.
 * Referencing either would add a forced dependency for a consumer that has {@code RestTemplate} but
 * no servlet stack — a batch or worker service — which Principle II forbids. Reading through the
 * {@link RequestAttributes} interface, which is in spring-web, avoids both (research.md §3).
 *
 * <p><strong>Never reports a stale value</strong> (FR-007). {@link RequestContextHolder} uses a
 * non-inheritable {@code ThreadLocal} that the servlet clears when the request completes, so a
 * pooled or async thread observes nothing and takes the fallback. That is the correct outcome:
 * attributing a call to an endpoint it did not come from would send an operator to innocent code
 * during an incident, which is worse than reporting no endpoint at all. Nothing here propagates the
 * value across a thread hand-off (FR-008) — that is the consuming service's responsibility, per the
 * starter's existing documented limitation.
 *
 * <p>Replaceable: declare your own {@code InboundUriResolver} bean to change this behaviour.
 */
public class InboundUriResolver {

    /**
     * {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE}, as a literal — see the class note on
     * why the constant is not referenced directly.
     */
    private static final String BEST_MATCHING_PATTERN_ATTRIBUTE =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    /**
     * @return the matched endpoint pattern's path, or {@link DestinationUriResolver#UNKNOWN} when
     *         no inbound request can be established on this thread. Never null, never blank.
     */
    public String resolve() {
        try {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return DestinationUriResolver.UNKNOWN;
            }

            Object pattern = attributes.getAttribute(
                    BEST_MATCHING_PATTERN_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (!(pattern instanceof String matched) || matched.isBlank()) {
                return DestinationUriResolver.UNKNOWN;
            }

            String path = stripQueryAndFragment(matched).trim();
            return path.isBlank() ? DestinationUriResolver.UNKNOWN : path;
        } catch (Exception resolutionFailure) {
            // Constitution Principle I — telemetry is best-effort, the call is not.
            return DestinationUriResolver.UNKNOWN;
        }
    }

    private static String stripQueryAndFragment(String pattern) {
        int cut = -1;
        for (char delimiter : new char[]{'?', '#'}) {
            int at = pattern.indexOf(delimiter);
            if (at >= 0 && (cut < 0 || at < cut)) {
                cut = at;
            }
        }
        return cut < 0 ? pattern : pattern.substring(0, cut);
    }
}
