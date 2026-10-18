package com.techinterviewer.ai.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * A provider endpoint on the JDK's built-in HTTP server, for contract tests of the real providers: it records every
 * request (method, path, headers, body) and answers with what the test scripted (status, headers, body, delay).
 */
public final class StubHttpServer implements AutoCloseable {

    /** A request as received. */
    public record Received(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    /** What to answer. */
    public record Reply(int status, String body, Map<String, String> headers, Duration delay) {
        public static Reply json(int status, String body) {
            return new Reply(status, body, Map.of(), Duration.ZERO);
        }

        public Reply withHeader(String name, String value) {
            return new Reply(status, body, Map.of(name, value), delay);
        }

        public Reply after(Duration wait) {
            return new Reply(status, body, headers, wait);
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile Function<Received, Reply> handler = request -> Reply.json(500, "{\"error\":\"no reply scripted\"}");

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void reply(Function<Received, Reply> handler) {
        this.handler = handler;
    }

    public void reply(Reply reply) {
        this.handler = request -> reply;
    }

    public List<Received> received() {
        return new ArrayList<>(received);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Received request = new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                Map.copyOf(exchange.getRequestHeaders()), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        received.add(request);
        Reply reply = handler.apply(request);
        if (!reply.delay().isZero()) {
            try {
                Thread.sleep(reply.delay());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException clientGone) {
            // The client timed out and closed the connection: nothing left to answer.
        } finally {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
