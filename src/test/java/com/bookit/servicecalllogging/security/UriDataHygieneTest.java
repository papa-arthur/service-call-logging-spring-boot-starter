package com.bookit.servicecalllogging.security;

import com.bookit.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.logging.OutboundCallRecord;
import com.bookit.servicecalllogging.metrics.OutboundCallMetrics;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.bookit.servicecalllogging.testsupport.FakeClientHttpResponse;
import com.bookit.servicecalllogging.testsupport.TestProperties;
import com.bookit.servicecalllogging.uri.UriTemplateCapture;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 003, T015 — SC-008 and the adversarial test the amended Principle VII Verification clause
 * now requires: a URI carrying userinfo credentials and a token-bearing query string must leak
 * neither into any log entry nor into any metric tag.
 *
 * <p>This passes <em>by construction</em> rather than by redaction: the recorded value is the URI's
 * path component, and credentials live in the authority while tokens live in the query, so neither
 * is representable in what is recorded. That is the property being pinned here — not that a
 * scrubbing step happened to run.
 */
@ExtendWith(OutputCaptureExtension.class)
class UriDataHygieneTest {

    private static final String PASSWORD = "hunter2SECRET";
    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9SECRET";
    private static final String HOSTILE_URI =
            "https://alice:" + PASSWORD + "@payments.internal:8443/accounts/42?access_token=" + TOKEN;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void tearDown() {
        UriTemplateCapture.clear();
    }

    private OutboundCallInterceptor interceptor(CallLogger logger) {
        return new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"),
                bytes -> Optional.of(0),
                logger,
                new OutboundCallMetrics(this.registry, TestProperties.defaults()),
                TestProperties.defaults());
    }

    private void call(CallLogger logger) throws Exception {
        interceptor(logger).intercept(
                new MockClientHttpRequest(HttpMethod.GET, URI.create(HOSTILE_URI)),
                new byte[0],
                (req, body) -> new FakeClientHttpResponse(
                        "{\"responseCode\":0}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void neitherTheCredentialNorTheTokenReachesTheLoggedRecord() throws Exception {
        Recording recording = new Recording();

        call(recording);

        OutboundCallRecord record = recording.only();
        assertThat(record.destinationUri())
                .isEqualTo("/accounts/42")
                .doesNotContain(PASSWORD, TOKEN, "alice", "access_token", "payments.internal");
        assertThat(record.inboundUri()).doesNotContain(PASSWORD, TOKEN);
    }

    @Test
    void neitherTheCredentialNorTheTokenReachesAnyMetricTag() throws Exception {
        call(new Recording());

        List<String> everyTagValue = new ArrayList<>();
        this.registry.getMeters().forEach(meter ->
                meter.getId().getTags().forEach(tag -> everyTagValue.add(tag.getValue())));

        assertThat(everyTagValue).isNotEmpty();
        assertThat(everyTagValue)
                .as("no tag value on any meter may carry the credential or the token")
                .noneMatch(value -> value.contains(PASSWORD) || value.contains(TOKEN)
                        || value.contains("access_token") || value.contains("alice"));
    }

    @Test
    void neitherTheCredentialNorTheTokenAppearsInTheEmittedLogLine(CapturedOutput output) throws Exception {
        // The real CallLogger, so this covers the formatted output an operator actually reads.
        call(new CallLogger());

        assertThat(output).contains("destinationUri=/accounts/42");
        assertThat(output).doesNotContain(PASSWORD);
        assertThat(output).doesNotContain(TOKEN);
        assertThat(output).doesNotContain("access_token");
        assertThat(output).doesNotContain("alice");
    }

    @Test
    void aHostileTemplateIsAlsoReducedToItsPathOnEverySurface() throws Exception {
        // Same guarantee when the value arrives as a captured template rather than a raw URI.
        URI expanded = URI.create(HOSTILE_URI);
        UriTemplateCapture.capture(
                "https://alice:" + PASSWORD + "@payments.internal:8443/accounts/{id}?access_token=" + TOKEN,
                expanded);
        Recording recording = new Recording();

        call(recording);

        assertThat(recording.only().destinationUri())
                .isEqualTo("/accounts/{id}")
                .doesNotContain(PASSWORD, TOKEN, "alice");
    }

    private static final class Recording extends CallLogger {
        private final List<OutboundCallRecord> records = new ArrayList<>();

        @Override
        public void log(OutboundCallRecord record) {
            this.records.add(record);
        }

        OutboundCallRecord only() {
            assertThat(this.records).hasSize(1);
            return this.records.get(0);
        }
    }
}
