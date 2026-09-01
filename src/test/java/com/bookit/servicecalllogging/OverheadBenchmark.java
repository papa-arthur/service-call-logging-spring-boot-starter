package com.bookit.servicecalllogging;

import com.bookit.servicecalllogging.extractor.JacksonResponseCodeExtractor;
import com.bookit.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.logging.OutboundCallRecord;
import com.bookit.servicecalllogging.metrics.OutboundCallMetrics;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.bookit.servicecalllogging.testsupport.FakeClientHttpResponse;
import com.bookit.servicecalllogging.testsupport.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Measures the per-call instrumentation cost that Constitution Principle VI requires the README
 * to document as a bounded budget.
 *
 * <p>Excluded from both CI surefire executions (tagged {@code benchmark}) because timing
 * assertions are inherently flaky on shared runners. Run it deliberately when the numbers in the
 * README need refreshing:
 *
 * <pre>{@code mvn test -Dgroups=benchmark}</pre>
 *
 * <p>The transport is stubbed out, so what is measured is purely the starter's own work: name
 * resolution, header stamping, body buffering, JSON extraction, logging and the counter
 * increment. Real network latency dwarfs all of it.
 */
@Tag("benchmark")
class OverheadBenchmark {

    private static final int WARMUP = 20_000;
    private static final int ITERATIONS = 200_000;

    private static final byte[] SMALL_BODY =
            "{\"responseCode\":0,\"message\":\"OK\",\"data\":{\"id\":4242}}".getBytes(StandardCharsets.UTF_8);

    /** Discards records so logging I/O does not dominate the measurement. */
    private static final CallLogger SILENT_LOGGER = new CallLogger() {
        @Override
        public void log(OutboundCallRecord record) {
            // intentionally empty
        }
    };

    @Test
    void reportPerCallOverhead() throws Exception {
        report();
    }

    /** Also runnable directly, which is handy since CI excludes this class from both executions. */
    public static void main(String[] args) throws Exception {
        report();
    }

    private static void report() throws Exception {
        long baseline = measure(null);
        long instrumented = measure(interceptor());

        long overheadNanos = (instrumented - baseline) / ITERATIONS;
        System.out.printf("%n=== per-call instrumentation overhead (blocking path) ===%n");
        System.out.printf("baseline    : %6.2f us/call%n", baseline / (double) ITERATIONS / 1000);
        System.out.printf("instrumented: %6.2f us/call%n", instrumented / (double) ITERATIONS / 1000);
        System.out.printf("overhead    : %6d ns/call (%.2f us)%n", overheadNanos, overheadNanos / 1000.0);
        System.out.printf("body size   : %d bytes%n%n", SMALL_BODY.length);
    }

    private static OutboundCallInterceptor interceptor() {
        return new OutboundCallInterceptor(
                new DestinationNameResolver("bench-service"),
                new JacksonResponseCodeExtractor(),
                SILENT_LOGGER,
                new OutboundCallMetrics(new SimpleMeterRegistry(), TestProperties.defaults()),
                TestProperties.defaults());
    }

    private static long measure(OutboundCallInterceptor interceptor) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            oneCall(interceptor);
        }
        long start = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            oneCall(interceptor);
        }
        return System.nanoTime() - start;
    }

    private static void oneCall(OutboundCallInterceptor interceptor) throws Exception {
        MockClientHttpRequest request =
                new MockClientHttpRequest(HttpMethod.GET, URI.create("http://bench-target:8080/api"));

        ClientHttpResponse response;
        if (interceptor == null) {
            response = new FakeClientHttpResponse(SMALL_BODY);
        } else {
            response = interceptor.intercept(request, new byte[0],
                    (req, body) -> new FakeClientHttpResponse(SMALL_BODY));
        }
        // The caller always reads the body; include that cost on both sides.
        response.getBody().readAllBytes();
    }
}
