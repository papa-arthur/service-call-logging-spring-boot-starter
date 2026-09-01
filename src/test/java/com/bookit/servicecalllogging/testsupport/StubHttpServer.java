package com.bookit.servicecalllogging.testsupport;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal HTTP stub built on the JDK's own {@link HttpServer}.
 *
 * <p>Deliberately dependency-free: Constitution Principle II (Zero Forced Footprint) rules out
 * pulling MockWebServer/OkHttp into this build just to stub a response (see research.md D-009).
 */
public final class StubHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final List<Headers> receivedRequestHeaders = new CopyOnWriteArrayList<>();

    private volatile int status = 200;
    private volatile byte[] body = new byte[0];
    private volatile String contentType = "application/json";

    public StubHttpServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/", exchange -> {
            this.receivedRequestHeaders.add(exchange.getRequestHeaders());
            exchange.getRequestBody().readAllBytes();

            byte[] payload = this.body;
            if (this.contentType != null) {
                exchange.getResponseHeaders().set("Content-Type", this.contentType);
            }
            exchange.sendResponseHeaders(this.status, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
            exchange.close();
        });
        this.server.start();
    }

    public String baseUrl() {
        return "http://" + this.server.getAddress().getHostString() + ":" + this.server.getAddress().getPort();
    }

    public String url(String path) {
        return baseUrl() + path;
    }

    public int port() {
        return this.server.getAddress().getPort();
    }

    public StubHttpServer respondWith(int status, String body) {
        this.status = status;
        this.body = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        return this;
    }

    public StubHttpServer respondWith(int status, byte[] body) {
        this.status = status;
        this.body = body == null ? new byte[0] : body;
        return this;
    }

    public StubHttpServer respondWithEmptyBody(int status) {
        this.status = status;
        this.body = new byte[0];
        return this;
    }

    public StubHttpServer contentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    /**
     * @return the headers of the most recent request the stub received
     */
    public Headers lastRequestHeaders() {
        if (this.receivedRequestHeaders.isEmpty()) {
            throw new IllegalStateException("no request has reached the stub server yet");
        }
        return this.receivedRequestHeaders.get(this.receivedRequestHeaders.size() - 1);
    }

    public String lastRequestHeader(String name) {
        return lastRequestHeaders().getFirst(name);
    }

    public int requestCount() {
        return this.receivedRequestHeaders.size();
    }

    /**
     * @return a loopback URL on a port with nothing listening, for network-error testing
     */
    public static String unreachableUrl() throws IOException {
        int freePort;
        try (ServerSocket throwaway = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            freePort = throwaway.getLocalPort();
        }
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + freePort;
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}
