package com.bookit.servicecalllogging.interceptor;

import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.logging.OutboundCallRecord;
import com.bookit.servicecalllogging.metrics.Outcome;
import com.bookit.servicecalllogging.metrics.OutboundCallMetrics;
import com.bookit.servicecalllogging.metrics.ResponseCodeResult;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Instant;

/**
 * Instruments the blocking {@code RestTemplate} path: stamps the correlation headers, reads the
 * response envelope's {@code responseCode}, and emits telemetry.
 *
 * <p>Structured entirely around Constitution Principle I. Every instrumentation step sits
 * inside a guard, and the guards are arranged so that <em>whatever</em> fails, the caller still
 * gets the response it would have got without the starter:
 *
 * <ul>
 *   <li>Header stamping failure — the call proceeds unstamped.</li>
 *   <li>A transport failure from {@code execute()} — telemetry records
 *       {@code network-error} and the original exception is re-thrown untouched.</li>
 *   <li>Buffering or classification failure — the raw response is returned as-is.</li>
 *   <li>Logging or metrics failure — swallowed; the buffered response (with its body fully
 *       intact) is still returned.</li>
 * </ul>
 */
public class OutboundCallInterceptor implements ClientHttpRequestInterceptor {

    private final DestinationNameResolver destinationNameResolver;
    private final ResponseCodeExtractor responseCodeExtractor;
    private final CallLogger callLogger;
    private final OutboundCallMetrics outboundCallMetrics;
    private final ServiceCallLoggingProperties properties;

    /**
     * Creates an interceptor that logs but records no metrics — the shape used when the
     * consumer has no {@code MeterRegistry}.
     */
    public OutboundCallInterceptor(DestinationNameResolver destinationNameResolver,
                                   ResponseCodeExtractor responseCodeExtractor,
                                   CallLogger callLogger,
                                   ServiceCallLoggingProperties properties) {
        this(destinationNameResolver, responseCodeExtractor, callLogger, null, properties);
    }

    /**
     * @param outboundCallMetrics the metrics recorder, or null when Actuator is absent from the
     *                            consumer's classpath
     */
    public OutboundCallInterceptor(DestinationNameResolver destinationNameResolver,
                                   ResponseCodeExtractor responseCodeExtractor,
                                   CallLogger callLogger,
                                   OutboundCallMetrics outboundCallMetrics,
                                   ServiceCallLoggingProperties properties) {
        this.destinationNameResolver = destinationNameResolver;
        this.responseCodeExtractor = responseCodeExtractor;
        this.callLogger = callLogger;
        this.outboundCallMetrics = outboundCallMetrics;
        this.properties = properties;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        Instant startedAt = Instant.now();
        String source = this.destinationNameResolver.getSourceName();
        String destination = DestinationNameResolver.UNKNOWN;
        String httpMethod = request.getMethod().name();

        try {
            String hint = request.getHeaders().getFirst(this.properties.serviceNameHintHeader());
            destination = this.destinationNameResolver.resolve(request.getURI(), hint);
            request.getHeaders().set(this.properties.sourceHeaderName(), source);
            request.getHeaders().set(this.properties.destinationHeaderName(), destination);
        } catch (Exception stampingFailure) {
            // Never block the call over a header we could not add.
            safeLogWarn(source, destination, stampingFailure);
        }

        ClientHttpResponse rawResponse;
        try {
            rawResponse = execution.execute(request, body);
        } catch (IOException | RuntimeException transportFailure) {
            recordNetworkError(source, destination, httpMethod, startedAt);
            throw transportFailure;
        }

        BufferingClientHttpResponse buffered = null;
        try {
            int statusCode = rawResponse.getStatusCode().value();
            String statusGroup = HttpStatusGroup.classify(statusCode);

            buffered = new BufferingClientHttpResponse(rawResponse, this.properties.maxBodyBytes());
            ResponseCodeResult result = buffered.peek(this.responseCodeExtractor);

            safeLog(new OutboundCallRecord(source, destination, httpMethod, statusCode,
                    statusGroup, result.rawCode(), startedAt));
            safeRecordMetrics(destination, result.outcome(), statusGroup);

            return buffered;
        } catch (Exception instrumentationFailure) {
            safeLogWarn(source, destination, instrumentationFailure);
            // Prefer the buffered wrapper when we have one: its body is intact either way, and
            // returning the raw response after a partial read would truncate the caller's body.
            return buffered != null ? buffered : rawResponse;
        }
    }

    private void recordNetworkError(String source, String destination, String httpMethod, Instant startedAt) {
        safeLog(new OutboundCallRecord(source, destination, httpMethod, null,
                HttpStatusGroup.NETWORK_ERROR, null, startedAt));
        safeRecordMetrics(destination, Outcome.ABSENT, HttpStatusGroup.NETWORK_ERROR);
    }

    /** Emits the log entry, absorbing any failure: telemetry is best-effort, the call is not. */
    private void safeLog(OutboundCallRecord record) {
        try {
            this.callLogger.log(record);
        } catch (Exception loggingFailure) {
            // The business call is already safe; there is nowhere to escalate this to.
        }
    }

    /**
     * Increments the counter when a recorder exists, under its own guard so that a metrics
     * failure can neither break the call nor suppress the log entry that precedes it.
     */
    private void safeRecordMetrics(String destination, Outcome outcome, String statusGroup) {
        if (this.outboundCallMetrics == null) {
            return;
        }
        try {
            this.outboundCallMetrics.record(destination, outcome, statusGroup);
        } catch (Exception metricsFailure) {
            // Same contract as logging: telemetry is best-effort.
        }
    }

    private void safeLogWarn(String source, String destination, Throwable error) {
        try {
            this.callLogger.logWarn(source, destination, error);
        } catch (Exception ignored) {
            // Nothing left to do — we must not surface anything to the caller.
        }
    }
}
