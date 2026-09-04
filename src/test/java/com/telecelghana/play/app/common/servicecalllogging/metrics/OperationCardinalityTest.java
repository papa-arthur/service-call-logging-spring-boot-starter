package com.telecelghana.play.app.common.servicecalllogging.metrics;

import com.telecelghana.play.app.common.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger;
import com.telecelghana.play.app.common.servicecalllogging.logging.OutboundCallRecord;
import com.telecelghana.play.app.common.servicecalllogging.operation.OperationResolver;
import com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.FakeClientHttpResponse;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.TestProperties;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * Spec 003, T028 — the operation half of SC-009, verified adversarially.
 *
 * <p>The URI half is {@code UriCardinalityTest} in User Story 1; this task asserts nothing about
 * URI tags.
 *
 * <p>The attack this defends against is the one the shape bound alone does not stop: every value
 * below satisfies {@code [A-Za-z0-9._-]{1,64}} while being unique per call. Only the distinct-value
 * cap keeps the series count bounded.
 */
class OperationCardinalityTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OperationResolver sharedResolver = new OperationResolver();

    private void callWithOperation(String operationHeader) throws Exception {
        OutboundCallInterceptor interceptor = new OutboundCallInterceptor(
                new DestinationNameResolver("my-service"),
                bytes -> Optional.of(0),
                new CallLogger() {
                    @Override
                    public void log(OutboundCallRecord record) {
                        // Silent: this test is about tag cardinality.
                    }
                },
                new OutboundCallMetrics(this.registry, TestProperties.defaults()),
                TestProperties.defaults(),
                null,
                new com.telecelghana.play.app.common.servicecalllogging.uri.DestinationUriResolver(),
                new com.telecelghana.play.app.common.servicecalllogging.uri.InboundUriResolver(),
                this.sharedResolver);

        MockClientHttpRequest request =
                new MockClientHttpRequest(HttpMethod.GET, URI.create("https://svc:8443/x"));
        if (operationHeader != null) {
            request.getHeaders().set(OperationResolver.HEADER_NAME, operationHeader);
        }

        interceptor.intercept(request, new byte[0],
                (req, body) -> new FakeClientHttpResponse(
                        "{\"responseCode\":0}".getBytes(StandardCharsets.UTF_8)));
    }

    private Set<String> distinctOperationTagValues() {
        return this.registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> tag.getKey().equals("operation"))
                .map(Tag::getValue)
                .collect(Collectors.toSet());
    }

    @Test
    void tenThousandUniqueOperationValuesStayWithinTheCap() throws Exception {
        for (int i = 0; i < 10_000; i++) {
            // Every one of these passes the shape bound — that is the point.
            callWithOperation("SendMoney-" + String.format("%05d", i));
        }

        assertThat(distinctOperationTagValues())
                .as("at most the %d admitted values plus the single undefined literal",
                        OperationResolver.MAX_DISTINCT_VALUES)
                .hasSizeLessThanOrEqualTo(OperationResolver.MAX_DISTINCT_VALUES + 1);
    }

    @Test
    void theOverflowCollapsesOntoTheUndefinedLiteral() throws Exception {
        for (int i = 0; i < 500; i++) {
            callWithOperation("Op" + i);
        }

        assertThat(distinctOperationTagValues())
                .as("everything past the cap must land on one shared value")
                .contains(OperationResolver.UNDEFINED);
    }

    @Test
    void aWellBehavedServiceNeverReachesTheCap() throws Exception {
        // The realistic case: a handful of named business operations, high volume.
        String[] operations = {"SendMoney", "ReverseTransfer", "CheckBalance"};
        for (int i = 0; i < 3_000; i++) {
            callWithOperation(operations[i % operations.length]);
        }

        assertThat(distinctOperationTagValues())
                .containsExactlyInAnyOrder("SendMoney", "ReverseTransfer", "CheckBalance");
    }

    @Test
    void callsWithNoOperationHeaderAllShareTheUndefinedValue() throws Exception {
        for (int i = 0; i < 200; i++) {
            callWithOperation(null);
        }

        assertThat(distinctOperationTagValues()).containsExactly(OperationResolver.UNDEFINED);
    }
}
