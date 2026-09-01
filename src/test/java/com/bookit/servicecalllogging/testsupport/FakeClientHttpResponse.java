package com.bookit.servicecalllogging.testsupport;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A single-read {@link ClientHttpResponse} over a fixed byte array, used to unit-test the
 * buffering decorator. {@link #getBody()} deliberately returns the <em>same</em> stream
 * instance on every call, exactly like a real network-backed response — so a test that
 * re-reads it without buffering sees an exhausted stream.
 */
public class FakeClientHttpResponse implements ClientHttpResponse {

    private final HttpStatusCode statusCode;
    private final InputStream body;
    private final HttpHeaders headers = new HttpHeaders();

    private boolean closed;

    public FakeClientHttpResponse(byte[] body) {
        this(HttpStatus.OK, body);
    }

    public FakeClientHttpResponse(HttpStatusCode statusCode, byte[] body) {
        this.statusCode = statusCode;
        this.body = new ByteArrayInputStream(body);
    }

    /**
     * @return a response whose body stream throws on the first read attempt
     */
    public static FakeClientHttpResponse failingBody() {
        return new FakeClientHttpResponse(HttpStatus.OK, new byte[0]) {
            @Override
            public InputStream getBody() throws IOException {
                throw new IOException("simulated body read failure");
            }
        };
    }

    @Override
    public HttpStatusCode getStatusCode() {
        return this.statusCode;
    }

    @Override
    public String getStatusText() {
        return this.statusCode.toString();
    }

    @Override
    public void close() {
        this.closed = true;
    }

    @Override
    public InputStream getBody() throws IOException {
        return this.body;
    }

    @Override
    public HttpHeaders getHeaders() {
        return this.headers;
    }

    public boolean isClosed() {
        return this.closed;
    }
}
