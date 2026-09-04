package com.bookit.servicecalllogging.uri;

import java.net.URI;

/**
 * Carries a URI template from the point where it is still known to the point where it is needed.
 *
 * <p>On the blocking path there is no other route. Verified against Spring Framework 6.1.21: a
 * {@code ClientHttpRequestInterceptor} receives an already-expanded {@link URI}, and
 * {@code ClientHttpRequest} carries no per-request attribute map to read a template from
 * (research.md §1). The template is therefore captured inside the {@code UriTemplateHandler}, which
 * runs on the same thread as the interceptor for a synchronous {@code RestTemplate} exchange, and
 * read back here.
 *
 * <p><strong>Staleness is prevented structurally, not by discipline.</strong> The capture stores the
 * expanded URI alongside the template, and {@link #readFor(URI)} returns the template only when
 * that stored URI equals the URI of the request actually being intercepted. This matters because
 * {@code RestTemplate.exchange(URI, ...)} bypasses the handler completely — no {@code expand()}
 * call happens, so any pair still present belongs to an earlier call on this thread. Without the
 * equality check that earlier template would be silently attributed to the wrong call, which is a
 * worse failure than reporting no template at all: it names an endpoint the call never touched.
 *
 * <p>Reads clear the capture, and the instrumentation clears it again in a {@code finally} block,
 * so nothing leaks to the next call on a pooled thread.
 */
public final class UriTemplateCapture {

    private record Captured(String template, URI expandedUri) {
    }

    private static final ThreadLocal<Captured> CURRENT = new ThreadLocal<>();

    private UriTemplateCapture() {
    }

    /**
     * Records the template a handler just expanded. Nulls are ignored rather than stored, so a
     * partial capture can never be mistaken for a usable one.
     */
    public static void capture(String template, URI expandedUri) {
        if (template == null || template.isBlank() || expandedUri == null) {
            return;
        }
        CURRENT.set(new Captured(template, expandedUri));
    }

    /**
     * Returns the captured template if it was the one that produced {@code requestUri}, else null.
     * Always clears, whether the pair matched or was rejected as stale.
     *
     * @param requestUri the URI of the request actually being intercepted
     * @return the template, or null when nothing was captured or the capture belongs to another call
     */
    public static String readFor(URI requestUri) {
        Captured captured = CURRENT.get();
        CURRENT.remove();
        if (captured == null || requestUri == null) {
            return null;
        }
        return captured.expandedUri().equals(requestUri) ? captured.template() : null;
    }

    /** Discards any capture on this thread. Safe to call when there is none. */
    public static void clear() {
        CURRENT.remove();
    }
}
