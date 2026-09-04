package com.telecelghana.play.app.common.servicecalllogging.uri;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Spec 003, T013 — the destination URI's two surfaces.
 *
 * <p>The asymmetry is the whole point of FR-004: an untemplatable URI is recorded as its raw path
 * in the log entries, where the detail is wanted and costs nothing, but as a single placeholder in
 * the metric tag, where a per-request-unique value would be unbounded cardinality.
 */
class DestinationUriResolverTest {

    private final DestinationUriResolver resolver = new DestinationUriResolver();

    @AfterEach
    void tearDown() {
        UriTemplateCapture.clear();
    }

    @Test
    void aCapturedRelativeTemplateIsReportedOnBothSurfaces() {
        URI expanded = URI.create("https://svc:8443/accounts/42/transfers");
        UriTemplateCapture.capture("/accounts/{id}/transfers", expanded);

        DestinationUriResolver.Resolved resolved = this.resolver.resolve(expanded);

        assertThat(resolved.logValue()).isEqualTo("/accounts/{id}/transfers");
        assertThat(resolved.metricValue()).isEqualTo("/accounts/{id}/transfers");
    }

    @Test
    void anAbsoluteTemplateIsReducedToItsPath() {
        URI expanded = URI.create("https://svc:8443/accounts/42");
        UriTemplateCapture.capture("https://svc:8443/accounts/{id}", expanded);

        assertThat(this.resolver.resolve(expanded).logValue()).isEqualTo("/accounts/{id}");
    }

    @Test
    void anUntemplatableUriGivesTheRawPathInLogsAndThePlaceholderOnTheMetric() {
        URI raw = URI.create("https://svc:8443/accounts/12345/transfers");

        DestinationUriResolver.Resolved resolved = this.resolver.resolve(raw);

        assertThat(resolved.logValue())
                .as("detail is wanted in a log line (FR-004)")
                .isEqualTo("/accounts/12345/transfers");
        assertThat(resolved.metricValue())
                .as("a per-identifier-unique path must never become a metric tag (FR-004)")
                .isEqualTo(DestinationUriResolver.UNRESOLVED);
    }

    @Test
    void aNullUriGivesUnknownOnBothSurfaces() {
        DestinationUriResolver.Resolved resolved = this.resolver.resolve(null);

        assertThat(resolved.logValue()).isEqualTo(DestinationUriResolver.UNKNOWN);
        assertThat(resolved.metricValue()).isEqualTo(DestinationUriResolver.UNKNOWN);
    }

    // ===== FR-005: path component only, on every surface =====

    @Test
    void aQueryStringNeverReachesEitherSurface() {
        URI withQuery = URI.create("https://svc/search?token=SECRET123&q=bob");

        DestinationUriResolver.Resolved resolved = this.resolver.resolve(withQuery);

        assertThat(resolved.logValue()).isEqualTo("/search").doesNotContain("SECRET123", "token");
        assertThat(resolved.metricValue()).doesNotContain("SECRET123", "token");
    }

    @Test
    void userinfoCredentialsNeverReachEitherSurface() {
        URI withCreds = URI.create("https://alice:hunter2@svc:8443/accounts/7");

        DestinationUriResolver.Resolved resolved = this.resolver.resolve(withCreds);

        assertThat(resolved.logValue()).isEqualTo("/accounts/7").doesNotContain("hunter2", "alice");
        assertThat(resolved.metricValue()).doesNotContain("hunter2", "alice");
    }

    @Test
    void neitherSurfaceEverCarriesSchemeHostOrPort() {
        URI uri = URI.create("https://payments.internal:8443/charge");

        DestinationUriResolver.Resolved resolved = this.resolver.resolve(uri);

        assertThat(resolved.logValue()).isEqualTo("/charge")
                .doesNotContain("https", "payments.internal", "8443");
    }

    @Test
    void aTemplateCarryingAQueryIsAlsoStrippedToItsPath() {
        URI expanded = URI.create("https://svc/search?q=bob");
        UriTemplateCapture.capture("/search?q={term}", expanded);

        assertThat(this.resolver.resolve(expanded).logValue()).isEqualTo("/search");
    }

    // ===== FR-037: nothing here may throw =====

    @Test
    void aTemplateContainingBracesIsNotParsedAsAUriAndDoesNotThrow() {
        // URI.create("/a/{id}") throws — a template is not a legal URI. Path extraction must be
        // a string scan, never URI parsing (research.md §4).
        URI expanded = URI.create("https://svc/a/1");
        UriTemplateCapture.capture("/a/{id}", expanded);

        assertThatCode(() -> this.resolver.resolve(expanded)).doesNotThrowAnyException();
        assertThat(this.resolver.resolve(URI.create("https://svc/a/1"))).isNotNull();
    }

    @Test
    void aUriWithNoPathDegradesToRootRatherThanUnknown() {
        assertThat(this.resolver.resolve(URI.create("https://svc")).logValue()).isEqualTo("/");
    }

    @Test
    void anOpaqueUriDoesNotThrowAndDegradesSafely() {
        assertThatCode(() -> this.resolver.resolve(URI.create("mailto:ops@example.com")))
                .doesNotThrowAnyException();
    }
}
