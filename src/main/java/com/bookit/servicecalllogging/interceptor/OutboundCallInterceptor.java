package com.bookit.servicecalllogging.interceptor;

import com.bookit.servicecalllogging.EnvelopeFieldExtractor;
import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.logging.OutboundCallRecord;
import com.bookit.servicecalllogging.metrics.Outcome;
import com.bookit.servicecalllogging.metrics.OutboundCallMetrics;
import com.bookit.servicecalllogging.metrics.ResponseCodeResult;
import com.bookit.servicecalllogging.operation.OperationResolver;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.bookit.servicecalllogging.uri.DestinationUriResolver;
import com.bookit.servicecalllogging.uri.InboundUriResolver;
import com.bookit.servicecalllogging.uri.UriTemplateCapture;
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
    private final EnvelopeFieldExtractor envelopeFieldExtractor;
    private final DestinationUriResolver destinationUriResolver;
    private final InboundUriResolver inboundUriResolver;
    private final OperationResolver operationResolver;

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
        this(destinationNameResolver, responseCodeExtractor, callLogger, outboundCallMetrics,
                properties, null);
    }

    /**
     * @param envelopeFieldExtractor the envelope matching engine used for the message and the
     *                               applicable successful value, or null when Jackson is absent
     */
    public OutboundCallInterceptor(DestinationNameResolver destinationNameResolver,
                                   ResponseCodeExtractor responseCodeExtractor,
                                   CallLogger callLogger,
                                   OutboundCallMetrics outboundCallMetrics,
                                   ServiceCallLoggingProperties properties,
                                   EnvelopeFieldExtractor envelopeFieldExtractor) {
        this(destinationNameResolver, responseCodeExtractor, callLogger, outboundCallMetrics,
                properties, envelopeFieldExtractor,
                new DestinationUriResolver(), new InboundUriResolver(), new OperationResolver());
    }

    /**
     * @param destinationUriResolver resolves the destination URI's log and metric surfaces
     * @param inboundUriResolver     resolves the path of the inbound request being handled
     */
    public OutboundCallInterceptor(DestinationNameResolver destinationNameResolver,
                                   ResponseCodeExtractor responseCodeExtractor,
                                   CallLogger callLogger,
                                   OutboundCallMetrics outboundCallMetrics,
                                   ServiceCallLoggingProperties properties,
                                   EnvelopeFieldExtractor envelopeFieldExtractor,
                                   DestinationUriResolver destinationUriResolver,
                                   InboundUriResolver inboundUriResolver,
                                   OperationResolver operationResolver) {
        this.destinationNameResolver = destinationNameResolver;
        this.responseCodeExtractor = responseCodeExtractor;
        this.callLogger = callLogger;
        this.outboundCallMetrics = outboundCallMetrics;
        this.properties = properties;
        this.envelopeFieldExtractor = envelopeFieldExtractor;
        this.destinationUriResolver = destinationUriResolver;
        this.inboundUriResolver = inboundUriResolver;
        this.operationResolver = operationResolver;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        try {
            return doIntercept(request, body, execution);
        } finally {
            // Whatever happened above — success, transport failure, instrumentation failure — no
            // capture may survive into the next call on this thread (spec 003, T022).
            UriTemplateCapture.clear();
        }
    }

    private ClientHttpResponse doIntercept(HttpRequest request, byte[] body,
                                           ClientHttpRequestExecution execution) throws IOException {
        Instant startedAt = Instant.now();
        // System.nanoTime() for the measurement, Instant for the record's timestamp: the former
        // is monotonic and immune to wall-clock adjustment, the latter is what an operator reads.
        long startedNanos = System.nanoTime();
        String source = this.destinationNameResolver.getSourceName();
        String destination = DestinationNameResolver.UNKNOWN;
        String httpMethod = request.getMethod().name();
        // Read the capture the UriTemplateHandler left immediately before us, while it is still
        // ours; read-and-clear means this happens exactly once per call.
        DestinationUriResolver.Resolved destinationUri = safeResolveDestinationUri(request);
        String inboundUri = safeResolveInboundUri();
        String operation = safeResolveOperation(request);

        try {
            String hint = request.getHeaders().getFirst(this.properties.serviceNameHintHeader());
            destination = this.destinationNameResolver.resolve(request.getURI(), hint);
            request.getHeaders().set(this.properties.sourceHeaderName(), source);
            request.getHeaders().set(this.properties.destinationHeaderName(), destination);
        } catch (Exception stampingFailure) {
            // Never block the call over a header we could not add.
            safeLogWarn(source, destination, stampingFailure);
        }

        // Emitted BEFORE dispatch, so a call that never returns still leaves a record of the
        // attempt (FR-031). Inside the same guard as every other telemetry step: a logging
        // failure must neither delay the request nor break it (Constitution Principle I).
        safeLogRequest(new OutboundCallRecord(source, destination, httpMethod, null, null, null,
                null, destinationUri.logValue(), inboundUri, operation, startedAt));

        ClientHttpResponse rawResponse;
        try {
            rawResponse = execution.execute(request, body);
        } catch (IOException | RuntimeException transportFailure) {
            recordNetworkError(source, destination, httpMethod, startedAt,
                    destinationUri, inboundUri, operation, System.nanoTime() - startedNanos);
            throw transportFailure;
        }

        BufferingClientHttpResponse buffered = null;
        try {
            int statusCode = rawResponse.getStatusCode().value();
            String statusGroup = HttpStatusGroup.classify(statusCode);

            buffered = new BufferingClientHttpResponse(rawResponse, this.properties.maxBodyBytes());
            ResponseCodeResult result =
                    buffered.peek(this.responseCodeExtractor, this.envelopeFieldExtractor);

            // The log entry gets the log surface (raw path when untemplatable); the metric gets
            // the metric surface (placeholder instead) — FR-004. Operation still awaits US2.
            safeLog(new OutboundCallRecord(source, destination, httpMethod, statusCode,
                    statusGroup, result.rawCode(), buffered.peekedMessage(),
                    destinationUri.logValue(), inboundUri, operation, startedAt));
            safeRecordMetrics(destination, result.outcome(), statusGroup,
                    destinationUri.metricValue(), inboundUri, operation,
                    System.nanoTime() - startedNanos);

            return buffered;
        } catch (Exception instrumentationFailure) {
            safeLogWarn(source, destination, instrumentationFailure);
            // Prefer the buffered wrapper when we have one: its body is intact either way, and
            // returning the raw response after a partial read would truncate the caller's body.
            return buffered != null ? buffered : rawResponse;
        }
    }

    private void recordNetworkError(String source, String destination, String httpMethod,
                                    Instant startedAt, DestinationUriResolver.Resolved destinationUri,
                                    String inboundUri, String operation, long elapsedNanos) {
        safeLog(new OutboundCallRecord(source, destination, httpMethod, null,
                HttpStatusGroup.NETWORK_ERROR, null, null,
                destinationUri.logValue(), inboundUri, operation, startedAt));
        safeRecordMetrics(destination, Outcome.ABSENT, HttpStatusGroup.NETWORK_ERROR,
                destinationUri.metricValue(), inboundUri, operation, elapsedNanos);
    }

    /**
     * Reads the operation header under its own guard. Read-only: the header is never modified, so
     * a value rejected for telemetry still reaches the destination untouched (FR-024).
     */
    private String safeResolveOperation(HttpRequest request) {
        try {
            return this.operationResolver.resolve(
                    request.getHeaders().getFirst(OperationResolver.HEADER_NAME));
        } catch (Exception resolutionFailure) {
            return OperationResolver.UNDEFINED;
        }
    }

    /**
     * Resolves the destination URI under its own guard. A resolution failure costs the dimension,
     * never the call (FR-037).
     */
    private DestinationUriResolver.Resolved safeResolveDestinationUri(HttpRequest request) {
        try {
            return this.destinationUriResolver.resolve(request.getURI());
        } catch (Exception resolutionFailure) {
            return new DestinationUriResolver.Resolved(
                    DestinationUriResolver.UNKNOWN, DestinationUriResolver.UNKNOWN);
        }
    }

    /** Same contract as above for the inbound URI. */
    private String safeResolveInboundUri() {
        try {
            return this.inboundUriResolver.resolve();
        } catch (Exception resolutionFailure) {
            return DestinationUriResolver.UNKNOWN;
        }
    }

    /** Same contract as {@link #safeLog}, for the send-time entry. */
    private void safeLogRequest(OutboundCallRecord record) {
        try {
            this.callLogger.logRequest(record);
        } catch (Exception loggingFailure) {
            // The request has not been dispatched yet and must not be affected by this.
        }
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
    private void safeRecordMetrics(String destination, Outcome outcome, String statusGroup,
                                   String destinationUri, String inboundUri, String operation,
                                   long elapsedNanos) {
        if (this.outboundCallMetrics == null) {
            return;
        }
        try {
            this.outboundCallMetrics.record(destination, outcome, statusGroup,
                    destinationUri, inboundUri, operation, elapsedNanos);
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
