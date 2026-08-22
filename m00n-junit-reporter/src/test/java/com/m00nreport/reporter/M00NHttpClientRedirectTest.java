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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the API key is allowed to travel.
 *
 * <p>The project API key rides in X-API-Key, a custom header. An HTTP client drops the standard
 * Authorization header when a redirect crosses to another host and keeps every custom one, so a
 * followed redirect would hand the key to whatever host the Location names. The ingest API never
 * redirects, so the client refuses one instead.</p>
 *
 * <p>Two servers, both local: the first answers 307 pointing at the second, and the second records
 * everything it is given. Nothing may reach it.</p>
 */
class M00NHttpClientRedirectTest {

    private HttpServer redirector;
    private HttpServer destination;
    private String redirectorUrl;

    private final List<String> keysSeenByDestination = new CopyOnWriteArrayList<>();
    private int redirectorRequests;

    /** The host the redirect points at. Records the API key of anything that arrives. */
    private void startDestination(String body) throws IOException {
        destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/", (HttpExchange exchange) -> {
            String key = exchange.getRequestHeaders().getFirst("X-API-Key");
            keysSeenByDestination.add(key != null ? key : "<none>");
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        destination.start();
    }

    /** The configured server. Answers every path with a redirect to the other host. */
    private void startRedirector(int status) throws IOException {
        redirector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String target = "http://127.0.0.1:" + destination.getAddress().getPort();
        redirector.createContext("/", (HttpExchange exchange) -> {
            redirectorRequests++;
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", target + exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        redirector.start();
        redirectorUrl = "http://127.0.0.1:" + redirector.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (redirector != null) {
            redirector.stop(0);
        }
        if (destination != null) {
            destination.stop(0);
        }
    }

    private M00NHttpClient client() {
        return new M00NHttpClient(M00NConfig.builder()
                .serverUrl(redirectorUrl)
                .apiKey("m00n_secret_key")
                .maxRetries(3)
                .timeout(2000)
                .build());
    }

    @Test
    @DisplayName("a 307 does not carry the API key to another host")
    void postDoesNotFollowARedirect() throws IOException {
        startDestination("{\"ok\":true}");
        startRedirector(307);

        assertThrows(IOException.class,
                () -> client().post("/api/ingest/v2/run/start", Map.of("launch", "x")));

        assertTrue(keysSeenByDestination.isEmpty(),
                "the redirect target was contacted and given: " + keysSeenByDestination);
        assertEquals(1, redirectorRequests, "a redirect is a verdict, not a reason to try again");
    }

    @Test
    @DisplayName("an attachment upload does not follow a redirect either")
    void uploadDoesNotFollowARedirect() throws IOException {
        startDestination("{\"ok\":true}");
        startRedirector(307);

        assertThrows(IOException.class, () -> client().uploadBytes(
                "/api/ingest/v2/attachment/upload",
                Map.of("runId", "r1", "testId", "t1"),
                new byte[] { 1, 2, 3 },
                "screenshot.png",
                "image/png"));

        assertTrue(keysSeenByDestination.isEmpty(),
                "the file and the key were sent to the redirect target: " + keysSeenByDestination);
    }

    @Test
    @DisplayName("every redirect status is refused, not just 307")
    void allRedirectStatusesAreRefused() throws IOException {
        for (int status : new int[] { 301, 302, 303, 307, 308 }) {
            stop();
            keysSeenByDestination.clear();
            redirectorRequests = 0;
            startDestination("{\"ok\":true}");
            startRedirector(status);

            assertThrows(IOException.class,
                    () -> client().post("/api/ingest/v2/test/complete", Map.of()),
                    "HTTP " + status + " was treated as a success");
            assertTrue(keysSeenByDestination.isEmpty(),
                    "HTTP " + status + " leaked to the redirect target: " + keysSeenByDestination);
        }
    }

    @Test
    @DisplayName("the health probe stops at the redirect too")
    void healthCheckDoesNotFollowARedirect() throws IOException {
        // The probe sends no credential, but a host that only answers because it was redirected to
        // is not the configured server, so its verdict is worthless.
        startDestination("{\"ok\":true}");
        startRedirector(302);

        assertFalse(client().healthCheck(), "a redirect was read as a healthy server");
        assertTrue(keysSeenByDestination.isEmpty());
    }

    @Test
    @DisplayName("an ordinary response is unaffected")
    void normalResponsesStillWork() throws IOException {
        // The other direction: refusing redirects must not refuse anything else.
        destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/", (HttpExchange exchange) -> {
            byte[] bytes = "{\"runId\":\"r1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        destination.start();
        redirectorUrl = "http://127.0.0.1:" + destination.getAddress().getPort();

        assertEquals("r1", client().post("/api/ingest/v2/run/start", Map.of())
                .get("runId").getAsString());
    }
}
