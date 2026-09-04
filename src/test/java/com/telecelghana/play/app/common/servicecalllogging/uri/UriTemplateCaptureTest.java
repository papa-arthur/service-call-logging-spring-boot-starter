package com.telecelghana.play.app.common.servicecalllogging.uri;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 003, T012 — the blocking path's URI-template hand-off.
 *
 * <p>Verified against Spring Framework 6.1.21: a {@code ClientHttpRequestInterceptor} is handed an
 * already-expanded {@code URI} and has no attribute map to read a template from (research.md §1),
 * so the template is captured where it is still known — in the {@code UriTemplateHandler} — and
 * read back here.
 *
 * <p>The property that matters most is the stale-pair rejection: a {@code RestTemplate.exchange(URI, ...)}
 * overload bypasses the handler entirely, so no capture happens and whatever the thread last left
 * behind must NOT be attributed to the current call.
 */
class UriTemplateCaptureTest {

    @AfterEach
    void tearDown() {
        UriTemplateCapture.clear();
    }

    @Test
    void aCapturedTemplateIsReadableForTheUriItExpandedTo() {
        URI expanded = URI.create("https://svc:8443/accounts/42/transfers");
        UriTemplateCapture.capture("/accounts/{id}/transfers", expanded);

        assertThat(UriTemplateCapture.readFor(expanded)).isEqualTo("/accounts/{id}/transfers");
    }

    @Test
    void readingClearsTheCaptureSoItCannotBeReusedByTheNextCall() {
        URI expanded = URI.create("https://svc:8443/x");
        UriTemplateCapture.capture("/x", expanded);

        assertThat(UriTemplateCapture.readFor(expanded)).isEqualTo("/x");
        assertThat(UriTemplateCapture.readFor(expanded))
                .as("read-and-clear: a second read must not see the same capture")
                .isNull();
    }

    @Test
    void aCaptureForADifferentUriIsRejectedAsStale() {
        // The decisive case: RestTemplate.exchange(URI, ...) never calls expand(), so a capture
        // left by an earlier call on this thread must not be attributed to this one.
        UriTemplateCapture.capture("/accounts/{id}", URI.create("https://svc/accounts/1"));

        assertThat(UriTemplateCapture.readFor(URI.create("https://svc/orders/9")))
                .as("a pair whose expanded URI does not match the intercepted request is stale")
                .isNull();
    }

    @Test
    void aStalePairIsAlsoClearedSoItCannotLingerFurther() {
        UriTemplateCapture.capture("/accounts/{id}", URI.create("https://svc/accounts/1"));

        UriTemplateCapture.readFor(URI.create("https://svc/orders/9"));

        assertThat(UriTemplateCapture.readFor(URI.create("https://svc/accounts/1")))
                .as("rejecting a stale pair must also discard it")
                .isNull();
    }

    @Test
    void readingWithNothingCapturedYieldsNull() {
        assertThat(UriTemplateCapture.readFor(URI.create("https://svc/x"))).isNull();
    }

    @Test
    void readingWithANullRequestUriYieldsNullAndDoesNotThrow() {
        UriTemplateCapture.capture("/x", URI.create("https://svc/x"));

        assertThat(UriTemplateCapture.readFor(null)).isNull();
    }

    @Test
    void capturingNullsIsIgnoredRatherThanStored() {
        UriTemplateCapture.capture(null, URI.create("https://svc/x"));
        assertThat(UriTemplateCapture.readFor(URI.create("https://svc/x"))).isNull();

        UriTemplateCapture.capture("/x", null);
        assertThat(UriTemplateCapture.readFor(URI.create("https://svc/x"))).isNull();
    }

    @Test
    void aCaptureIsNotVisibleFromAnotherThread() throws Exception {
        URI expanded = URI.create("https://svc/x");
        UriTemplateCapture.capture("/x", expanded);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> other = pool.submit(() -> UriTemplateCapture.readFor(expanded));
            assertThat(other.get())
                    .as("the capture is thread-confined; a pooled thread must see nothing")
                    .isNull();
        } finally {
            pool.shutdownNow();
        }
    }
}
