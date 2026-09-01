package com.bookit.servicecalllogging.resolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Resolves the two correlation identities: this service's name (once, at construction) and
 * the destination service name (per call).
 *
 * <p>Deliberately free of any {@code spring-web} type so that the always-active root
 * auto-configuration can create this bean even in a consumer with no HTTP client on the
 * classpath (Constitution Principle II — adding the starter must never change startup
 * success). The caller extracts the hint header value and passes it in as a plain String.
 *
 * <p>Override by declaring your own {@code DestinationNameResolver} bean — for example to
 * normalise high-cardinality destination names before they become metric labels.
 */
public class DestinationNameResolver {

    /** Value reported when a name cannot be determined. */
    public static final String UNKNOWN = "unknown";

    private static final Logger log = LoggerFactory.getLogger(DestinationNameResolver.class);

    private static final String HTTPS_SCHEME = "https";
    private static final int DEFAULT_HTTPS_PORT = 443;
    private static final int DEFAULT_HTTP_PORT = 80;

    private final String sourceName;

    /**
     * @param applicationName the value of {@code spring.application.name}; when null or blank
     *                        the source name degrades to {@link #UNKNOWN} and a WARN is
     *                        emitted once, at startup. Initialisation never fails.
     */
    public DestinationNameResolver(String applicationName) {
        if (isBlank(applicationName)) {
            this.sourceName = UNKNOWN;
            log.warn("spring.application.name is not configured; source service name will be "
                    + "reported as \"{}\". Set spring.application.name to suppress this warning.", UNKNOWN);
        } else {
            this.sourceName = applicationName;
        }
    }

    /**
     * @return this service's name for the source correlation header; never null
     */
    public String getSourceName() {
        return this.sourceName;
    }

    /**
     * Resolves the destination service name in the documented priority order:
     * <ol>
     *   <li>the hint header value, used verbatim when non-blank;</li>
     *   <li>otherwise {@code host:port} derived from the request URI, where a missing port
     *       becomes 443 for {@code https} and 80 otherwise.</li>
     * </ol>
     *
     * @param uri            the outgoing request URI; may be null
     * @param hintHeaderValue the value of the configured hint header, or null when absent
     * @return the destination name, or {@link #UNKNOWN} if it cannot be determined; never null
     */
    public String resolve(URI uri, String hintHeaderValue) {
        if (!isBlank(hintHeaderValue)) {
            return hintHeaderValue;
        }
        if (uri == null) {
            return UNKNOWN;
        }
        String host = uri.getHost();
        if (isBlank(host)) {
            return UNKNOWN;
        }
        int port = uri.getPort();
        if (port == -1) {
            port = HTTPS_SCHEME.equalsIgnoreCase(uri.getScheme()) ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
        }
        return host + ":" + port;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
