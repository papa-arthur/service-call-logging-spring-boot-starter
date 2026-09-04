package com.bookit.servicecalllogging.uri;

import java.net.URI;

/**
 * Resolves the destination URI of an outbound call into the two forms the two surfaces need.
 *
 * <p>Why two forms rather than one string (FR-004): when the templated form cannot be determined,
 * the raw path is exactly what an operator wants in a log line, and exactly what must never become
 * a metric tag — a path carrying an account number is per-request-unique, so one series per
 * identifier. The log surface therefore gets the raw path and the metric surface gets a single
 * documented placeholder.
 *
 * <p>Every value returned is the URI's <strong>path component only</strong> (FR-005). That is what
 * makes the no-credential guarantee structural rather than procedural: credentials live in a URI's
 * userinfo component and tokens in its query string, and neither is part of a path, so there is no
 * redaction step that could be forgotten for some future way of supplying a URI.
 *
 * <p>Replaceable: declare your own {@code DestinationUriResolver} bean to change this behaviour.
 */
public class DestinationUriResolver {

    /** Recorded when a URI could not be determined at all — including no inbound request. */
    public static final String UNKNOWN = "unknown";

    /**
     * Recorded in a <strong>metric tag only</strong>, when a URI is known but its templated form
     * is not. Deliberately distinct from {@link #UNKNOWN} so an operator can tell "we could not
     * template this" from "there was no URI here".
     */
    public static final String UNRESOLVED = "unresolved";

    /**
     * One destination URI as the log entries and the metric each need it.
     *
     * @param logValue    templated path when known, else the raw path, else {@link #UNKNOWN}
     * @param metricValue templated path when known, else {@link #UNRESOLVED}, else {@link #UNKNOWN}
     */
    public record Resolved(String logValue, String metricValue) {
    }

    /**
     * @param requestUri the URI of the outbound request, already expanded; may be null
     * @return both surfaces; never null, and neither field is ever null or blank
     */
    public Resolved resolve(URI requestUri) {
        if (requestUri == null) {
            return new Resolved(UNKNOWN, UNKNOWN);
        }

        String template = UriTemplateCapture.readFor(requestUri);
        if (template != null) {
            String templatePath = pathOfTemplate(template);
            if (templatePath != null) {
                return new Resolved(templatePath, templatePath);
            }
        }

        String rawPath = pathOfUri(requestUri);
        return rawPath == null
                ? new Resolved(UNKNOWN, UNKNOWN)
                : new Resolved(rawPath, UNRESOLVED);
    }

    /**
     * Extracts the path from a URI template by scanning the string, never by parsing it as a URI.
     *
     * <p>A template such as {@code /a/{id}} is <em>not</em> a legal URI and {@code URI.create} on
     * it throws — which FR-037 forbids. So: skip an authority if one is present, then take
     * everything up to the first {@code ?} or {@code #}.
     */
    private String pathOfTemplate(String template) {
        String remainder = template;

        int schemeSeparator = remainder.indexOf("://");
        if (schemeSeparator >= 0) {
            int authorityStart = schemeSeparator + 3;
            int pathStart = remainder.indexOf('/', authorityStart);
            remainder = pathStart < 0 ? "/" : remainder.substring(pathStart);
        }

        remainder = beforeFirstOf(remainder, '?', '#');
        if (remainder.isBlank()) {
            return "/";
        }
        return remainder.startsWith("/") ? remainder : "/" + remainder;
    }

    /** The raw path of an already-expanded URI — never its authority, query or fragment. */
    private String pathOfUri(URI requestUri) {
        String path = requestUri.getRawPath();
        if (path == null) {
            // Opaque URIs (mailto:, urn:) have no path component at all.
            return null;
        }
        return path.isBlank() ? "/" : path;
    }

    private static String beforeFirstOf(String value, char... delimiters) {
        int cut = -1;
        for (char delimiter : delimiters) {
            int at = value.indexOf(delimiter);
            if (at >= 0 && (cut < 0 || at < cut)) {
                cut = at;
            }
        }
        return cut < 0 ? value : value.substring(0, cut);
    }
}
