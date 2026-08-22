package com.m00nreport.reporter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this client does with a response that is not the shape it expected, and what a
 * configuration value of zero does to it.
 *
 * Category 2 of the failure-mode checklist. The error path here was hardened once already; the
 * SUCCESS path was not, and it is the one that runs on every healthy call.
 */
class M00NHttpClientResponseTest {

    private HttpServer server;
    private AtomicInteger requestCount;
    private String baseUrl;

    private void start(Function<Integer, Reply> handler) throws IOException {
        requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            Reply reply = handler.apply(requestCount.incrementAndGet());
            byte[] body = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", reply.contentType);
            exchange.sendResponseHeaders(reply.status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private record Reply(int status, String body, String contentType) {
        static Reply json(int status, String body) {
            return new Reply(status, body, "application/json");
        }
        static Reply text(int status, String body) {
            return new Reply(status, body, "text/plain");
        }
    }

    private M00NHttpClient client(int maxRetries) {
        return new M00NHttpClient(M00NConfig.builder()
            .serverUrl(baseUrl)
            .apiKey("m00n_test_key")
            .maxRetries(maxRetries)
            .timeout(2000)
            .build());
    }

    // ------------------------------------------------ the success path's parsing

    @Test
    @DisplayName("a 200 whose body is a JSON string does not throw out of the client")
    void successWithJsonScalarDoesNotThrow() throws IOException {
        // gson.fromJson(body, JsonObject.class) raises JsonSyntaxException on anything that is not
        // an object, and JsonSyntaxException is unchecked - so it is not an IOException, it misses
        // every retry and catch in this file, and it propagates out of the reporter into the user's
        // test. A proxy or a load balancer answering a health-style body is enough to trigger it.
        start(n -> Reply.json(200, "\"OK\""));

        assertDoesNotThrow(() -> client(3).post("/api/ingest/v2/run/start", Map.of()));
    }

    @Test
    @DisplayName("a 200 whose body is a JSON array does not throw either")
    void successWithJsonArrayDoesNotThrow() throws IOException {
        start(n -> Reply.json(200, "[1,2,3]"));

        assertDoesNotThrow(() -> client(3).post("/api/ingest/v2/run/start", Map.of()));
    }

    @Test
    @DisplayName("a 200 with a body that is not JSON at all does not throw")
    void successWithNonJsonBodyDoesNotThrow() throws IOException {
        start(n -> Reply.text(200, "OK"));

        assertDoesNotThrow(() -> client(3).post("/api/ingest/v2/run/start", Map.of()));
    }

    @Test
    @DisplayName("a 200 with an empty body does not throw")
    void successWithEmptyBodyDoesNotThrow() throws IOException {
        start(n -> Reply.text(200, ""));

        assertDoesNotThrow(() -> client(3).post("/api/ingest/v2/run/start", Map.of()));
    }

    @Test
    @DisplayName("an ordinary object body still parses")
    void successWithObjectBodyStillWorks() throws IOException {
        // The other direction. A guard that swallowed every response would satisfy the four tests
        // above and break every run.
        start(n -> Reply.json(200, "{\"runId\":\"run-1\"}"));

        var response = client(3).post("/api/ingest/v2/run/start", Map.of());

        assertEquals("run-1", response.get("runId").getAsString());
    }

    // ---------------------------------------------------------- maxRetries = 0

    @Test
    @DisplayName("maxRetries=0 still sends the request once")
    void zeroRetriesStillSendsOnce() throws IOException {
        // The loop is `for (attempt = 1; attempt <= maxRetries; ...)`, so a configured 0 means the
        // body never runs: the client reports a failure without ever contacting the server, and
        // every test in the suite is lost while the network is perfectly healthy. Someone reading
        // "maxRetries" reasonably sets 0 to mean "try once, do not retry".
        start(n -> Reply.json(200, "{\"runId\":\"run-1\"}"));

        var response = client(0).post("/api/ingest/v2/run/start", Map.of());

        assertEquals(1, requestCount.get(), "zero retries must still mean one attempt");
        assertTrue(response.has("runId"));
    }

    @Test
    @DisplayName("a negative maxRetries is treated the same way")
    void negativeRetriesStillSendsOnce() throws IOException {
        start(n -> Reply.json(200, "{\"runId\":\"run-1\"}"));

        client(-5).post("/api/ingest/v2/run/start", Map.of());

        assertEquals(1, requestCount.get());
    }

    @Test
    @DisplayName("maxRetries=1 means one attempt, not two")
    void oneRetryMeansOneAttempt() throws IOException {
        // Pinning the boundary in the other direction, so a fix for the zero case cannot quietly
        // add an attempt everywhere.
        start(n -> Reply.json(500, "{}"));

        assertThrows(IOException.class, () -> client(1).post("/api/ingest/v2/run/start", Map.of()));

        assertEquals(1, requestCount.get());
    }
}
