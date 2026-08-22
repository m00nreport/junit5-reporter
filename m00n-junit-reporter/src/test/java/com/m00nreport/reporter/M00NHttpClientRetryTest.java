package com.m00nreport.reporter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the retry loop costs when the server says no.
 *
 * The loop raised its "permanent error" signal as an IOException from inside the
 * try whose own catch(IOException) implements the retry. So the signal was caught
 * one line later by the code it was meant to bypass, slept, and tried again. An
 * INVALID_API_KEY - a credential that will still be wrong in a millisecond - cost
 * three attempts and three seconds of sleeping on every call, for the whole
 * suite. Against a server that accepts connections and hangs, one call cost the
 * read timeout three times over, about 93 seconds, on the test thread.
 *
 * There was also no circuit breaker, so nothing ever concluded that the service
 * was gone and stopped asking.
 *
 * These tests use the JDK's own HttpServer rather than MockWebServer: it needs no
 * new dependency, runs offline, and is a real socket, so what is measured is the
 * client's behaviour rather than a stub's idea of it.
 */
class M00NHttpClientRetryTest {

    private HttpServer server;
    private AtomicInteger requestCount;
    private String baseUrl;

    /** Replies with whatever the handler returns for the request number it is given. */
    private void start(Function<Integer, Reply> handler) throws IOException {
        requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            int n = requestCount.incrementAndGet();
            Reply reply = handler.apply(n);
            byte[] body = reply.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
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

    private record Reply(int status, String body) {
        Reply {
        }
    }

    private static Reply json(int status, String code) {
        return new Reply(status, "{\"error\":\"denied\",\"code\":\"" + code + "\"}");
    }

    private M00NHttpClient client() {
        return new M00NHttpClient(M00NConfig.builder()
                .serverUrl(baseUrl)
                .apiKey("m00n_test_key")
                .maxRetries(3)
                .timeout(2000)
                .build());
    }

    // ---------------------------------------------------------------- permanent

    @Test
    @DisplayName("a permanent error is attempted exactly once")
    void permanentErrorIsNotRetried() throws IOException {
        start(n -> json(401, "INVALID_API_KEY"));
        M00NHttpClient client = client();

        long startedAt = System.nanoTime();
        assertThrows(IOException.class, () -> client.post("/api/ingest/v2/run/start", Map.of()));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(1, requestCount.get(),
                "a credential that is wrong now will still be wrong after a sleep");
        assertTrue(elapsedMs < 1000,
                "a permanent error must fail fast; it took " + elapsedMs + "ms, so it slept");
    }

    @Test
    @DisplayName("every permanent code the ingest service actually sends is honoured")
    void allPermanentCodesAreHonoured() throws IOException {
        // Taken from the server rather than from memory: these are the codes
        // services/ingest emits that retrying cannot fix. PROJECT_NOT_FOUND is
        // deliberately absent - the JS reporters list it and the service has never
        // sent it.
        List<String> codes = List.of(
                "API_KEY_REQUIRED", "INVALID_API_KEY", "INVALID_API_KEY_FORMAT",
                "SUBSCRIPTION_INACTIVE", "RUN_ACCESS_DENIED", "RUN_NOT_FOUND",
                "RUN_NOT_ACTIVE", "ATTACHMENT_TOO_LARGE", "RUN_ATTACHMENT_LIMIT_EXCEEDED",
                "PRESIGN_BINDING_UNKNOWN", "UPLOAD_NOT_FOUND");

        for (String code : codes) {
            stop();
            start(n -> json(400, code));
            M00NHttpClient client = client();

            assertThrows(IOException.class, () -> client.post("/api/ingest/v2/run/start", Map.of()));
            assertEquals(1, requestCount.get(), code + " was retried");
        }
    }

    @Test
    @DisplayName("HTTP 413 with no code in the body is still permanent")
    void bareStatus413IsPermanent() throws IOException {
        // The attachment limit is signalled by status alone on some paths, so the
        // rule cannot depend on the body parsing into something useful.
        start(n -> new Reply(413, "Payload Too Large"));
        M00NHttpClient client = client();

        assertThrows(IOException.class, () -> client.post("/api/ingest/v2/attachment/upload", Map.of()));

        assertEquals(1, requestCount.get());
    }

    // ---------------------------------------------------------------- transient

    @Test
    @DisplayName("a transient error is still retried, and still succeeds")
    void transientErrorIsRetried() throws IOException {
        // The other direction. A fix that stops retrying everything would be worse
        // than the bug: a single 502 from a load balancer would lose the call.
        start(n -> n < 3 ? new Reply(502, "{}") : new Reply(200, "{\"runId\":\"r1\"}"));
        M00NHttpClient client = client();

        client.post("/api/ingest/v2/run/start", Map.of());

        assertEquals(3, requestCount.get(), "two failures then a success is three requests");
    }

    @Test
    @DisplayName("a transient error that never clears gives up after maxRetries")
    void transientErrorGivesUpEventually() throws IOException {
        start(n -> new Reply(503, "{\"code\":\"STORAGE_UNAVAILABLE\"}"));
        M00NHttpClient client = client();

        assertThrows(IOException.class, () -> client.post("/api/ingest/v2/run/start", Map.of()));

        assertEquals(3, requestCount.get(), "maxRetries is 3, so three attempts and no more");
    }

    // ---------------------------------------------------------- circuit breaker

    @Test
    @DisplayName("a wrong credential stops the reporter asking for the rest of the run")
    void fatalCredentialErrorOpensTheBreaker() throws IOException {
        start(n -> json(401, "INVALID_API_KEY"));
        M00NHttpClient client = client();

        assertThrows(IOException.class, () -> client.post("/api/ingest/v2/run/start", Map.of()));
        int afterFirst = requestCount.get();

        for (int i = 0; i < 20; i++) {
            try {
                client.post("/api/ingest/v2/test/complete", Map.of());
            } catch (IOException expected) {
                // still an error, just not a network one
            }
        }

        assertEquals(afterFirst, requestCount.get(),
                "nothing after the first call should have reached the network");
    }

    @Test
    @DisplayName("repeated service failures eventually stop the reporter asking")
    void repeatedFailuresOpenTheBreaker() throws IOException {
        start(n -> new Reply(503, "{}"));
        M00NHttpClient client = client();

        for (int i = 0; i < 20; i++) {
            try {
                client.post("/api/ingest/v2/test/complete", Map.of());
            } catch (IOException expected) {
                // expected
            }
        }

        // Without a breaker this is 20 calls x 3 attempts = 60 requests, plus 60
        // seconds of sleeping. The exact threshold matters less than the fact that
        // it converges.
        assertTrue(requestCount.get() < 30,
                "expected the client to stop trying; it made " + requestCount.get() + " requests");
    }

    @Test
    @DisplayName("a per-request permanent error does not disable the whole run")
    void perRequestPermanentErrorDoesNotOpenTheBreaker() throws IOException {
        // One oversized attachment must not silence the reporting of every test
        // that follows it. This is the distinction the breaker has to make.
        start(n -> n == 1
                ? json(400, "ATTACHMENT_TOO_LARGE")
                : new Reply(200, "{\"ok\":true}"));
        M00NHttpClient client = client();

        assertThrows(IOException.class, () -> client.post("/api/ingest/v2/attachment/upload", Map.of()));
        client.post("/api/ingest/v2/test/complete", Map.of());

        assertEquals(2, requestCount.get(), "the second call must still go out");
    }

    // ------------------------------------------------------------------ success

    @Test
    @DisplayName("a success resets the failure count")
    void successResetsTheBreaker() throws IOException {
        // Otherwise a long suite with occasional blips would accumulate its way to
        // a disabled reporter against a perfectly healthy server.
        start(n -> n % 2 == 1 ? new Reply(503, "{}") : new Reply(200, "{\"ok\":true}"));
        M00NHttpClient client = client();

        for (int i = 0; i < 10; i++) {
            try {
                client.post("/api/ingest/v2/test/complete", Map.of());
            } catch (IOException ignored) {
                // half of them fail by construction
            }
        }

        assertTrue(requestCount.get() >= 10,
                "an intermittent server must not trip the breaker; got " + requestCount.get());
    }
}
