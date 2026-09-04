package com.bookit.servicecalllogging.filter;

import com.bookit.servicecalllogging.EnvelopeFieldExtractor;
import com.bookit.servicecalllogging.EnvelopeMatch;
import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.ServiceCallLoggingProperties;
import com.bookit.servicecalllogging.interceptor.HttpStatusGroup;
import com.bookit.servicecalllogging.logging.CallLogger;
import com.bookit.servicecalllogging.logging.OutboundCallRecord;
import com.bookit.servicecalllogging.metrics.OutboundCallMetrics;
import com.bookit.servicecalllogging.metrics.Outcome;
import com.bookit.servicecalllogging.metrics.ResponseCodeResult;
import com.bookit.servicecalllogging.operation.OperationResolver;
import com.bookit.servicecalllogging.resolver.DestinationNameResolver;
import com.bookit.servicecalllogging.uri.DestinationUriResolver;
import com.bookit.servicecalllogging.uri.InboundUriResolver;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Optional;

/**
 * Instruments the reactive {@code WebClient} path.
 *
 * <p>A reactive body can only be consumed once, so the filter multicasts it: the body flux is
 * wrapped in {@link Flux#cache()} and handed to two subscribers — a side channel that reads a
 * bounded prefix for {@code responseCode} extraction, and the business caller, which receives
 * the response with the cached flux substituted for its body. Every original buffer reaches the
 * caller (FR-011).
 *
 * <p><strong>The side channel never consumes the shared buffers.</strong> {@code cache()}
 * replays the <em>same</em> {@link DataBuffer} instances to both subscribers, so reading from
 * them — or from a composite that owns them — would advance their read position and leave the
 * caller with an empty body. Instead the side channel copies bytes out at absolute indices via
 * {@link DataBuffer#toByteBuffer(int, ByteBuffer, int, int)}, which leaves read positions and
 * reference counts untouched, and it stops copying altogether once the cap is reached so the
 * starter's own memory use stays bounded (Constitution Principle VI).
 *
 * <p>Per Constitution Principle I every telemetry step is guarded, and a transport error is
 * re-propagated to the caller unchanged after being recorded as {@code network-error}.
 */
public class OutboundCallExchangeFilter implements ExchangeFilterFunction {

    private final DestinationNameResolver destinationNameResolver;
    private final ResponseCodeExtractor responseCodeExtractor;
    private final CallLogger callLogger;
    private final OutboundCallMetrics outboundCallMetrics;
    private final DestinationUriResolver destinationUriResolver;
    private final InboundUriResolver inboundUriResolver;
    private final OperationResolver operationResolver;

    /**
     * {@code DefaultWebClient.URI_TEMPLATE_ATTRIBUTE}, as a literal.
     *
     * <p>Verified present in spring-webflux 6.1.21: {@code DefaultWebClient} publishes the URI
     * template under this request attribute, so the reactive path needs no {@code ThreadLocal}
     * hand-off — the framework already carries the template on the request (research.md §2). The
     * key is used as a string rather than referencing the internal class.
     */
    private static final String WEBCLIENT_URI_TEMPLATE_ATTRIBUTE =
            "org.springframework.web.reactive.function.client.WebClient.uriTemplate";
    private final ServiceCallLoggingProperties properties;
    private final EnvelopeFieldExtractor envelopeFieldExtractor;

    /**
     * Creates a filter that logs but records no metrics — the shape used when the consumer has
     * no {@code MeterRegistry}.
     */
    public OutboundCallExchangeFilter(DestinationNameResolver destinationNameResolver,
                                      ResponseCodeExtractor responseCodeExtractor,
                                      CallLogger callLogger,
                                      ServiceCallLoggingProperties properties) {
        this(destinationNameResolver, responseCodeExtractor, callLogger, null, properties);
    }

    /**
     * @param outboundCallMetrics the metrics recorder, or null when Actuator is absent from the
     *                            consumer's classpath
     */
    public OutboundCallExchangeFilter(DestinationNameResolver destinationNameResolver,
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
    public OutboundCallExchangeFilter(DestinationNameResolver destinationNameResolver,
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
     * @param inboundUriResolver     resolves the inbound request path; on this path it is expected
     *                               to yield the fallback, since context propagation across the
     *                               reactive boundary is the consuming service's responsibility
     */
    public OutboundCallExchangeFilter(DestinationNameResolver destinationNameResolver,
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

    /**
     * Resolves the destination URI on the reactive path.
     *
     * <p>The template comes from the request attribute {@code WebClient} sets, not from a
     * {@code ThreadLocal}: on this path the framework carries it on the request itself, so there
     * is no hand-off to get wrong and no staleness to guard against. A {@code WebClient} call
     * built from a pre-made {@code URI} carries no such attribute and correctly degrades to the
     * raw path in logs with the placeholder on the metric (FR-004).
     */
    private DestinationUriResolver.Resolved resolveDestinationUri(ClientRequest request) {
        try {
            Object template = request.attributes().get(WEBCLIENT_URI_TEMPLATE_ATTRIBUTE);
            if (template instanceof String uriTemplate && !uriTemplate.isBlank()) {
                com.bookit.servicecalllogging.uri.UriTemplateCapture.capture(uriTemplate, request.url());
            }
            return this.destinationUriResolver.resolve(request.url());
        } catch (Exception resolutionFailure) {
            return new DestinationUriResolver.Resolved(
                    DestinationUriResolver.UNKNOWN, DestinationUriResolver.UNKNOWN);
        }
    }

    /**
     * Reads the operation header on the reactive path. Read-only: {@code ClientRequest.headers()}
     * is inspected, never rebuilt, so a value rejected for telemetry still reaches the destination
     * exactly as the calling code set it (FR-024).
     */
    private String safeResolveOperation(ClientRequest request) {
        try {
            return this.operationResolver.resolve(
                    request.headers().getFirst(OperationResolver.HEADER_NAME));
        } catch (Exception resolutionFailure) {
            return OperationResolver.UNDEFINED;
        }
    }

    /** Guarded like every other telemetry step (FR-037). */
    private String safeResolveInboundUri() {
        try {
            return this.inboundUriResolver.resolve();
        } catch (Exception resolutionFailure) {
            return DestinationUriResolver.UNKNOWN;
        }
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        Instant startedAt = Instant.now();
        // Monotonic clock for the measurement; the Instant above is the record's timestamp.
        long startedNanos = System.nanoTime();
        String source = this.destinationNameResolver.getSourceName();
        String httpMethod = request.method().name();

        String destination;
        ClientRequest outgoing;
        try {
            String hint = request.headers().getFirst(this.properties.serviceNameHintHeader());
            destination = this.destinationNameResolver.resolve(request.url(), hint);
            outgoing = ClientRequest.from(request)
                    .header(this.properties.sourceHeaderName(), source)
                    .header(this.properties.destinationHeaderName(), destination)
                    .build();
        } catch (Exception stampingFailure) {
            // Never block the call over a header we could not add.
            safeLogWarn(source, DestinationNameResolver.UNKNOWN, stampingFailure);
            return next.exchange(request);
        }

        String resolvedDestination = destination;
        DestinationUriResolver.Resolved destinationUri = resolveDestinationUri(outgoing);
        String inboundUri = safeResolveInboundUri();
        String operation = safeResolveOperation(outgoing);

        // Before dispatch, for the same reason as the blocking path (FR-031).
        safeLogRequest(new OutboundCallRecord(source, resolvedDestination, httpMethod, null, null,
                null, null, destinationUri.logValue(), inboundUri, operation, startedAt));

        return next.exchange(outgoing)
                .flatMap(response -> instrument(response, source, resolvedDestination, httpMethod,
                        startedAt, startedNanos, destinationUri, inboundUri, operation))
                .onErrorResume(transportFailure -> {
                    safeLog(new OutboundCallRecord(source, resolvedDestination, httpMethod, null,
                            HttpStatusGroup.NETWORK_ERROR, null, null,
                            destinationUri.logValue(), inboundUri, operation, startedAt));
                    safeRecordMetrics(resolvedDestination, Outcome.ABSENT, HttpStatusGroup.NETWORK_ERROR,
                            destinationUri.metricValue(), inboundUri, operation,
                            System.nanoTime() - startedNanos);
                    // The business call must still fail exactly as it would have.
                    return Mono.error(transportFailure);
                });
    }

    private Mono<ClientResponse> instrument(ClientResponse response, String source, String destination,
                                            String httpMethod, Instant startedAt, long startedNanos,
                                            DestinationUriResolver.Resolved destinationUri,
                                            String inboundUri, String operation) {
        int statusCode;
        String statusGroup;
        Flux<DataBuffer> cachedBody;
        int cap;
        try {
            statusCode = response.statusCode().value();
            statusGroup = HttpStatusGroup.classify(statusCode);
            cap = this.properties.maxBodyBytes();
            cachedBody = response.bodyToFlux(DataBuffer.class).cache();
        } catch (Exception setupFailure) {
            safeLogWarn(source, destination, setupFailure);
            return Mono.just(response);
        }

        Mono<Interpreted> parsed = cachedBody
                .reduceWith(() -> new BoundedBodyPrefix(cap), BoundedBodyPrefix::append)
                .map(this::interpret)
                .defaultIfEmpty(Interpreted.ABSENT)
                .onErrorReturn(Interpreted.ABSENT);

        return parsed
                .doOnNext(interpreted -> {
                    // Operation still awaits US2 on this path.
                    safeLog(new OutboundCallRecord(source, destination, httpMethod,
                            statusCode, statusGroup, interpreted.result().rawCode(),
                            interpreted.message(),
                            destinationUri.logValue(), inboundUri, operation, startedAt));
                    safeRecordMetrics(destination, interpreted.result().outcome(), statusGroup,
                            destinationUri.metricValue(), inboundUri, operation,
                            System.nanoTime() - startedNanos);
                })
                .then(Mono.fromCallable(() -> response.mutate().body(cachedBody).build()))
                .onErrorResume(instrumentationFailure -> {
                    safeLogWarn(source, destination, instrumentationFailure);
                    return Mono.just(response.mutate().body(cachedBody).build());
                });
    }

    private Interpreted interpret(BoundedBodyPrefix prefix) {
        if (prefix.overflowed()) {
            // FR-013 (spec 001): past the cap we do not parse at all.
            return Interpreted.ABSENT;
        }
        byte[] bytes = prefix.bytes();
        EnvelopeMatch match = safeMatch(bytes);
        try {
            Optional<Integer> code = this.responseCodeExtractor.extract(bytes);
            if (code == null || code.isEmpty()) {
                return new Interpreted(ResponseCodeResult.ABSENT, match.message());
            }
            return new Interpreted(ResponseCodeResult.of(code.get(), match.successfulValue()),
                    match.message());
        } catch (Exception extractorFailure) {
            // The caller's buffers were never touched — only telemetry is lost.
            return new Interpreted(ResponseCodeResult.ABSENT, match.message());
        }
    }

    /**
     * Runs the envelope matching engine under its own guard: a failure here costs the message
     * and the configured successful value, never the call (Constitution Principle I).
     */
    private EnvelopeMatch safeMatch(byte[] bytes) {
        if (this.envelopeFieldExtractor == null) {
            return EnvelopeMatch.NONE;
        }
        try {
            EnvelopeMatch match = this.envelopeFieldExtractor.extract(bytes);
            return match == null ? EnvelopeMatch.NONE : match;
        } catch (Exception envelopeFailure) {
            return EnvelopeMatch.NONE;
        }
    }

    /**
     * The two independently-determined halves of a call's business outcome: the classified code
     * and the message.
     */
    private record Interpreted(ResponseCodeResult result, String message) {

        static final Interpreted ABSENT = new Interpreted(ResponseCodeResult.ABSENT, null);
    }

    /** Same contract as {@link #safeLog}, for the send-time entry. */
    private void safeLogRequest(OutboundCallRecord record) {
        try {
            this.callLogger.logRequest(record);
        } catch (Exception loggingFailure) {
            // Nothing has been exchanged yet; this must not affect it.
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

    /**
     * Accumulates at most {@code cap} bytes copied out of the response buffers, and remembers
     * whether the body ran past that ceiling. Copies are made at absolute indices so the shared
     * buffers keep their read position and reference count; once full, nothing is copied at all.
     */
    private static final class BoundedBodyPrefix {

        private final int cap;
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

        private boolean overflowed;

        BoundedBodyPrefix(int cap) {
            this.cap = Math.max(0, cap);
        }

        BoundedBodyPrefix append(DataBuffer buffer) {
            int available = buffer.readableByteCount();
            if (available == 0) {
                return this;
            }
            int room = this.cap - this.sink.size();
            if (available > room) {
                this.overflowed = true;
            }
            int toCopy = Math.min(available, room);
            if (toCopy > 0) {
                byte[] chunk = new byte[toCopy];
                buffer.toByteBuffer(buffer.readPosition(), ByteBuffer.wrap(chunk), 0, toCopy);
                this.sink.write(chunk, 0, toCopy);
            }
            return this;
        }

        boolean overflowed() {
            return this.overflowed;
        }

        byte[] bytes() {
            return this.sink.toByteArray();
        }
    }
}
