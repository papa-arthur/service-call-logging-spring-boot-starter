package com.bookit.servicecalllogging.interceptor;

import com.bookit.servicecalllogging.EnvelopeFieldExtractor;
import com.bookit.servicecalllogging.EnvelopeMatch;
import com.bookit.servicecalllogging.ResponseCodeExtractor;
import com.bookit.servicecalllogging.metrics.ResponseCodeResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Arrays;
import java.util.Optional;

/**
 * A {@link ClientHttpResponse} decorator that lets the starter read a bounded prefix of the
 * response body for {@code responseCode} extraction while guaranteeing the business caller
 * still receives every original byte (FR-011, FR-013).
 *
 * <p>Spring's own buffering wrapper reads the whole body with no ceiling, which would breach
 * Constitution Principle VI for large or streamed responses. This wrapper reads at most
 * {@code maxBodyBytes} and takes one extra byte purely to learn whether more data follows:
 *
 * <ul>
 *   <li><strong>Body fits within the cap</strong> — the bytes are cached and
 *       {@link #getBody()} hands out a fresh {@link ByteArrayInputStream} on every call, so
 *       the body is genuinely re-readable.</li>
 *   <li><strong>Body exceeds the cap</strong> — no parsing is attempted at all
 *       ({@code responseCode=absent}) and {@link #getBody()} returns a
 *       {@link SequenceInputStream} that replays the buffered prefix and then continues
 *       streaming the untouched remainder from the delegate. Streaming semantics are
 *       preserved past the cap.</li>
 *   <li><strong>Reading the body failed</strong> — the delegate's own stream is returned
 *       unchanged so the caller sees exactly the I/O behaviour it would have seen without
 *       instrumentation.</li>
 * </ul>
 *
 * <p>{@link #peek} never throws and is idempotent.
 */
public class BufferingClientHttpResponse implements ClientHttpResponse {

    private enum BodyState {
        /** {@link #peek} has not run, or reading the body failed: fall through to the delegate. */
        NOT_BUFFERED,
        /** The whole body fits within the cap and is cached; fully re-readable. */
        FULLY_BUFFERED,
        /** The body is larger than the cap; the cached prefix is replayed then streamed. */
        OVERSIZED
    }

    private final ClientHttpResponse delegate;
    private final int maxBodyBytes;

    private BodyState state = BodyState.NOT_BUFFERED;
    private byte[] cachedBody;
    private ResponseCodeResult peekResult;
    private String peekedMessage;

    /**
     * @param delegate     the real response; never null
     * @param maxBodyBytes the ceiling on buffered bytes; {@code 0} skips parsing entirely while
     *                     still delivering the full body to the caller
     */
    public BufferingClientHttpResponse(ClientHttpResponse delegate, int maxBodyBytes) {
        this.delegate = delegate;
        this.maxBodyBytes = Math.max(0, maxBodyBytes);
    }

    /**
     * Reads the bounded body prefix and asks the extractor to interpret it.
     *
     * <p>Never throws: every failure mode — an I/O error, an oversized body, a misbehaving
     * extractor, even one that violates the contract by returning null — degrades to
     * {@link ResponseCodeResult#ABSENT}. Calling this more than once returns the first result
     * without touching the body again.
     *
     * @param extractor the extraction strategy to apply; never null
     * @return the interpreted result; never null
     */
    public ResponseCodeResult peek(ResponseCodeExtractor extractor) {
        return peek(extractor, null);
    }

    /**
     * Reads the bounded body prefix once and interprets it twice: the code through
     * {@code extractor} (the consumer's own bean when they have supplied one), and the message
     * plus the applicable successful value through {@code envelopeFieldExtractor}. Both read the
     * same cached bytes — one body read, no additional I/O.
     *
     * <p>Never throws, for either extractor, and is idempotent in exactly the same way as
     * {@link #peek(ResponseCodeExtractor)}.
     *
     * @param extractor              the code extraction strategy; never null
     * @param envelopeFieldExtractor the envelope matching engine, or null when Jackson is absent
     *                               from the consumer's classpath — in which case no message is
     *                               read and the built-in successful value applies
     * @return the interpreted result; never null
     */
    public ResponseCodeResult peek(ResponseCodeExtractor extractor,
                                   EnvelopeFieldExtractor envelopeFieldExtractor) {
        if (this.peekResult != null) {
            return this.peekResult;
        }

        byte[] prefix;
        int overflowByte;
        try {
            InputStream source = this.delegate.getBody();
            prefix = source.readNBytes(this.maxBodyBytes);
            // One extra read is the only reliable way to distinguish "body ended exactly at the
            // cap" from "body continues past the cap" — readNBytes cannot tell us.
            overflowByte = source.read();
        } catch (Exception readFailure) {
            this.state = BodyState.NOT_BUFFERED;
            this.peekResult = ResponseCodeResult.ABSENT;
            return this.peekResult;
        }

        if (overflowByte == -1) {
            this.cachedBody = prefix;
            this.state = BodyState.FULLY_BUFFERED;
        } else {
            // Keep the sentinel byte we consumed; it belongs to the caller's body.
            this.cachedBody = Arrays.copyOf(prefix, prefix.length + 1);
            this.cachedBody[prefix.length] = (byte) overflowByte;
            this.state = BodyState.OVERSIZED;
            // FR-013: past the cap we do not parse at all.
            this.peekResult = ResponseCodeResult.ABSENT;
            return this.peekResult;
        }

        EnvelopeMatch match = applyEnvelopeExtractor(envelopeFieldExtractor, this.cachedBody);
        this.peekedMessage = match.message();
        this.peekResult = applyExtractor(extractor, this.cachedBody, match.successfulValue());
        return this.peekResult;
    }

    /**
     * @return the message read during {@link #peek}, or null when absent, unreadable, or peek
     *         has not run yet
     */
    public String peekedMessage() {
        return this.peekedMessage;
    }

    private static ResponseCodeResult applyExtractor(ResponseCodeExtractor extractor, byte[] body,
                                                     int successfulValue) {
        try {
            Optional<Integer> code = extractor.extract(body);
            if (code == null || code.isEmpty()) {
                return ResponseCodeResult.ABSENT;
            }
            return ResponseCodeResult.of(code.get(), successfulValue);
        } catch (Exception extractorFailure) {
            // The body is already cached, so the caller is unaffected — only telemetry is lost.
            return ResponseCodeResult.ABSENT;
        }
    }

    private static EnvelopeMatch applyEnvelopeExtractor(EnvelopeFieldExtractor extractor, byte[] body) {
        if (extractor == null) {
            return EnvelopeMatch.NONE;
        }
        try {
            EnvelopeMatch match = extractor.extract(body);
            return match == null ? EnvelopeMatch.NONE : match;
        } catch (Exception extractorFailure) {
            // Same contract as the code extractor: telemetry may be lost, the call may not.
            return EnvelopeMatch.NONE;
        }
    }

    @Override
    public InputStream getBody() throws IOException {
        return switch (this.state) {
            case FULLY_BUFFERED -> new ByteArrayInputStream(this.cachedBody);
            case OVERSIZED -> new SequenceInputStream(
                    new ByteArrayInputStream(this.cachedBody), this.delegate.getBody());
            case NOT_BUFFERED -> this.delegate.getBody();
        };
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
        return this.delegate.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
        return this.delegate.getStatusText();
    }

    @Override
    public HttpHeaders getHeaders() {
        return this.delegate.getHeaders();
    }

    @Override
    public void close() {
        this.delegate.close();
    }
}
