package com.telecelghana.play.app.common.servicecalllogging;

import com.telecelghana.play.app.common.servicecalllogging.extractor.JacksonEnvelopeFieldExtractor;
import com.telecelghana.play.app.common.servicecalllogging.extractor.JacksonResponseCodeExtractor;
import com.telecelghana.play.app.common.servicecalllogging.interceptor.OutboundCallInterceptor;
import com.telecelghana.play.app.common.servicecalllogging.logging.CallLogger;
import com.telecelghana.play.app.common.servicecalllogging.logging.OutboundCallRecord;
import com.telecelghana.play.app.common.servicecalllogging.metrics.OutboundCallMetrics;
import com.telecelghana.play.app.common.servicecalllogging.resolver.DestinationNameResolver;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.FakeClientHttpResponse;
import com.telecelghana.play.app.common.servicecalllogging.testsupport.TestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    /**
     * Discards records so logging I/O does not dominate the measurement.
     *
     * <p><strong>Both</strong> entry methods must be overridden. Since spec 003 a call emits two
     * entries — {@code logRequest} before dispatch and {@code log} on completion — and overriding
     * only {@code log} leaves the send-time entry doing real SLF4J I/O, which silently turns this
     * into a measurement of the logging framework rather than of the starter's own work. That is
     * the same trap the README warns adopters about for {@code CallLogger} subclasses; it caught
     * this benchmark first.
     */
    private static final CallLogger SILENT_LOGGER = new CallLogger() {
        @Override
        public void logRequest(OutboundCallRecord record) {
            // intentionally empty
        }

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
        long defaultConfig = measure(interceptor(List.of()));
        long threeCombinations = measure(interceptor(List.of(
                // the matching combination is deliberately last: worst case for the list walk
                new ServiceCallLoggingProperties.Envelope("statusCode", "message", 0),
                new ServiceCallLoggingProperties.Envelope("code", "detail", 0),
                new ServiceCallLoggingProperties.Envelope("responseCode", "message", 0))));

        System.out.printf("%n=== per-call instrumentation overhead (blocking path) ===%n");
        System.out.printf("baseline                       : %6.2f us/call%n", perCallMicros(baseline));
        System.out.printf("instrumented, no envelopes     : %6.2f us/call%n", perCallMicros(defaultConfig));
        System.out.printf("instrumented, 3 combinations   : %6.2f us/call%n", perCallMicros(threeCombinations));
        System.out.printf("overhead, no envelopes         : %6d ns/call (%.2f us)%n",
                overheadNanos(baseline, defaultConfig), overheadNanos(baseline, defaultConfig) / 1000.0);
        System.out.printf("overhead, 3 combinations       : %6d ns/call (%.2f us)%n",
                overheadNanos(baseline, threeCombinations),
                overheadNanos(baseline, threeCombinations) / 1000.0);
        System.out.printf("body size                      : %d bytes%n%n", SMALL_BODY.length);
    }

    private static double perCallMicros(long totalNanos) {
        return totalNanos / (double) ITERATIONS / 1000;
    }

    private static long overheadNanos(long baseline, long instrumented) {
        return (instrumented - baseline) / ITERATIONS;
    }

    /**
     * Wires the interceptor exactly as the auto-configuration does, including the envelope
     * matching engine — so the figure reflects what a real consumer actually pays.
     */
    private static OutboundCallInterceptor interceptor(List<ServiceCallLoggingProperties.Envelope> envelopes) {
        return new OutboundCallInterceptor(
                new DestinationNameResolver("bench-service"),
                new JacksonResponseCodeExtractor(new JacksonEnvelopeFieldExtractor(envelopes)),
                SILENT_LOGGER,
                new OutboundCallMetrics(new SimpleMeterRegistry(), TestProperties.defaults()),
                TestProperties.defaults(),
                new JacksonEnvelopeFieldExtractor(envelopes));
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
