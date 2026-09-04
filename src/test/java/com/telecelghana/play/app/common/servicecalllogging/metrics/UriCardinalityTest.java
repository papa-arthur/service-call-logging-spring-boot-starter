package com.telecelghana.play.app.common.servicecalllogging.metrics;

import com.telecelghana.play.app.common.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger;
import com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.FakeClientHttpResponse;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.TestProperties;
import com.telecelghana.play.app.common.servicecalllogging.uri.UriTemplateCapture;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 003, T059 — the URI half of SC-009, verified adversarially.
 *
 * <p>SC-009 is a bounded-cardinality guarantee, so it is proved by trying to break it: thousands of
 * calls to per-request-unique paths must not produce thousands of metric series. This is the
 * requirement FR-004's placeholder exists to satisfy, and the reason the metric surface differs
 * from the log surface at all.
 *
 * <p>The operation half of SC-009 is {@code OperationCardinalityTest} in User Story 2.
 */
class UriCardinalityTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void tearDown() {
        UriTemplateCapture.clear();
    }

    private OutboundCallInterceptor interceptor() {
        return new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"),
                bytes -> Optional.of(0),
                new CallLogger() {
                    @Override
                    public void log(com.telecelghana.play.app.common.servicecalllogging.logging.OutboundCallRecord record) {
                        // Silent: this test is about tag cardinality, not log output.
                    }
                },
                new OutboundCallMetrics(this.registry, TestProperties.defaults()),
                TestProperties.defaults());
    }

    private void call(String uri) throws Exception {
        interceptor().intercept(
                new MockClientHttpRequest(HttpMethod.GET, URI.create(uri)),
                new byte[0],
                (req, body) -> new FakeClientHttpResponse(
                        "{\"responseCode\":0}".getBytes(StandardCharsets.UTF_8)));
    }

    private Set<String> distinctTagValues(String tagKey) {
        return this.registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> tag.getKey().equals(tagKey))
                .map(Tag::getValue)
                .collect(Collectors.toSet());
    }

    @Test
    void twoThousandUntemplatableUrisCollapseToTheSinglePlaceholder() throws Exception {
        for (int i = 0; i < 2_000; i++) {
            call("https://svc:8443/accounts/" + i + "/transfers");
        }

        assertThat(distinctTagValues("destination_uri"))
                .as("a raw, per-identifier-unique path must never become a metric tag value")
                .containsExactly("unresolved");
    }

    @Test
    void aFixedTemplateInventoryYieldsOneTagValuePerTemplate() throws Exception {
        // The bound is the consuming service's own route inventory, not its traffic volume.
        String[] templates = {"/accounts/{id}/transfers", "/orders/{id}", "/health"};

        for (int i = 0; i < 900; i++) {
            String template = templates[i % templates.length];
            URI expanded = URI.create("https://svc:8443" + template.replace("{id}", String.valueOf(i)));
            UriTemplateCapture.capture(template, expanded);
            call(expanded.toString());
        }

        assertThat(distinctTagValues("destination_uri"))
                .as("900 calls across 3 templates must yield exactly 3 tag values")
                .containsExactlyInAnyOrder("/accounts/{id}/transfers", "/orders/{id}", "/health");
    }

    @Test
    void mixedTemplatedAndUntemplatableTrafficStaysBoundedByTemplatesPlusOnePlaceholder() throws Exception {
        for (int i = 0; i < 500; i++) {
            if (i % 2 == 0) {
                URI expanded = URI.create("https://svc/orders/" + i);
                UriTemplateCapture.capture("/orders/{id}", expanded);
                call(expanded.toString());
            } else {
                call("https://svc/legacy/" + i + "/thing");
            }
        }

        assertThat(distinctTagValues("destination_uri"))
                .containsExactlyInAnyOrder("/orders/{id}", "unresolved");
    }

    @Test
    void theInboundUriDimensionIsBoundedToUnknownWithNoRequestInScope() throws Exception {
        for (int i = 0; i < 300; i++) {
            call("https://svc/x/" + i);
        }

        assertThat(distinctTagValues("inbound_uri"))
                .as("no inbound request in scope for any of these calls")
                .containsExactly("unknown");
    }

    @Test
    void theLogSurfaceKeepsTheDetailTheMetricSurfaceDiscards() throws Exception {
        // The other side of FR-004: the raw path is still available where it costs nothing.
        java.util.List<String> loggedUris = new java.util.ArrayList<>();
        new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"),
                bytes -> Optional.of(0),
                new CallLogger() {
                    @Override
                    public void log(com.telecelghana.play.app.common.servicecalllogging.logging.OutboundCallRecord record) {
                        loggedUris.add(record.destinationUri());
                    }
                },
                new OutboundCallMetrics(this.registry, TestProperties.defaults()),
                TestProperties.defaults())
                .intercept(new MockClientHttpRequest(HttpMethod.GET,
                                URI.create("https://svc/accounts/98765/transfers")),
                        new byte[0],
                        (req, body) -> new FakeClientHttpResponse(
                                "{\"responseCode\":0}".getBytes(StandardCharsets.UTF_8)));

        assertThat(loggedUris).containsExactly("/accounts/98765/transfers");
        assertThat(distinctTagValues("destination_uri")).containsExactly("unresolved");
    }
}
