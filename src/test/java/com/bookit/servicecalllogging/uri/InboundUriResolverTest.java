package com.bookit.servicecalllogging.uri;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Spec 003, T014 — the inbound request URI.
 *
 * <p>FR-007's real requirement is negative: never report a stale value. A pooled or async thread
 * must yield the fallback, because naming an endpoint the call did not come from is a defect, not
 * degraded telemetry — it sends an operator to innocent code mid-incident.
 */
class InboundUriResolverTest {

    private static final String BEST_MATCHING_PATTERN =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

    private final InboundUriResolver resolver = new InboundUriResolver();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void bindRequestWithPattern(String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (pattern != null) {
            request.setAttribute(BEST_MATCHING_PATTERN, pattern);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void theMatchedEndpointPatternIsReported() {
        bindRequestWithPattern("/api/v1/payments/{id}");

        assertThat(this.resolver.resolve()).isEqualTo("/api/v1/payments/{id}");
    }

    @Test
    void noRequestContextGivesUnknown() {
        // A scheduled task, a startup hook, a CLI entry point — no inbound request exists.
        assertThat(this.resolver.resolve()).isEqualTo(DestinationUriResolver.UNKNOWN);
    }

    @Test
    void aRequestWithNoMatchedPatternGivesUnknown() {
        bindRequestWithPattern(null);

        assertThat(this.resolver.resolve()).isEqualTo(DestinationUriResolver.UNKNOWN);
    }

    @Test
    void aBlankPatternGivesUnknown() {
        bindRequestWithPattern("   ");

        assertThat(this.resolver.resolve()).isEqualTo(DestinationUriResolver.UNKNOWN);
    }

    @Test
    void aNonStringPatternAttributeGivesUnknownRatherThanThrowing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(BEST_MATCHING_PATTERN, 42);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThatCode(() -> this.resolver.resolve()).doesNotThrowAnyException();
        assertThat(this.resolver.resolve()).isEqualTo(DestinationUriResolver.UNKNOWN);
    }

    @Test
    void aPatternCarryingAQueryStringIsStrippedToItsPath() {
        bindRequestWithPattern("/api/v1/payments?token=SECRET");

        assertThat(this.resolver.resolve()).isEqualTo("/api/v1/payments").doesNotContain("SECRET");
    }

    @Test
    void aPooledThreadSeesUnknownAndNeverThePreviousRequestsPath() throws Exception {
        // FR-007 — the decisive case. RequestContextHolder is a non-inheritable ThreadLocal, so a
        // handed-off thread observes nothing rather than a stale value; this pins that behaviour.
        bindRequestWithPattern("/api/v1/payments");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> onAnotherThread = pool.submit(this.resolver::resolve);

            assertThat(onAnotherThread.get())
                    .as("a pooled thread must never inherit the caller's inbound URI")
                    .isEqualTo(DestinationUriResolver.UNKNOWN);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void resolvingAfterTheRequestCompletesGivesUnknown() {
        bindRequestWithPattern("/api/v1/payments");
        assertThat(this.resolver.resolve()).isEqualTo("/api/v1/payments");

        RequestContextHolder.resetRequestAttributes();

        assertThat(this.resolver.resolve()).isEqualTo(DestinationUriResolver.UNKNOWN);
    }
}
