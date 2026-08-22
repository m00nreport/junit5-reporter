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
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an attachment name and content type may put on the wire.
 *
 * <p>Both come from the test that produced the attachment, so both hold whatever a filesystem or a
 * caller allows. They are written into the headers of a multipart part, where a CR or an LF ends
 * the header and turns the rest into an injected line inside an authenticated upload.</p>
 *
 * <p>The body is read back off a real socket rather than from the client's idea of it, because the
 * question is what the server receives.</p>
 */
class M00NHttpClientUploadHeaderTest {

    private HttpServer server;
    private String baseUrl;
    private volatile String receivedBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", (HttpExchange exchange) -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            // ISO-8859-1 keeps one byte to one char, so what is asserted on is the wire itself.
            receivedBody = new String(raw, StandardCharsets.ISO_8859_1);
            byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
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

    private M00NHttpClient client() {
        return new M00NHttpClient(M00NConfig.builder()
                .serverUrl(baseUrl)
                .apiKey("m00n_test_key")
                .maxRetries(1)
                .timeout(2000)
                .build());
    }

    private void upload(String filename, String contentType) throws IOException {
        client().uploadBytes(
                "/api/ingest/v2/attachment/upload",
                Map.of("runId", "r1", "testId", "t1"),
                new byte[] { 1, 2, 3 },
                filename,
                contentType);
    }

    /** True when the injected name begins a line of its own, which is what injection means. */
    private boolean hasInjectedHeaderLine() {
        return Arrays.stream(receivedBody.split("\r\n"))
                .anyMatch(line -> line.startsWith("X-Injected:"));
    }

    @Test
    @DisplayName("a CR LF in the attachment name cannot start a header line")
    void nameCannotInjectAHeader() throws IOException {
        upload("evil\"\r\nX-Injected: yes\r\n\r\nshot.png", "image/png");

        assertFalse(hasInjectedHeaderLine(), "the name became a header line:\n" + receivedBody);
        assertTrue(receivedBody.contains(
                        "filename=\"evil%22%0D%0AX-Injected: yes%0D%0A%0D%0Ashot.png\""),
                "expected the name percent-encoded in place; got:\n" + receivedBody);
    }

    @Test
    @DisplayName("a CR LF in the content type cannot start a header line")
    void contentTypeCannotInjectAHeader() throws IOException {
        // A quoted parameter value is the way through: it holds any character, and the part header
        // is written from the string verbatim.
        upload("shot.png", "image/png; x=\"\r\nX-Injected: yes\"");

        assertFalse(hasInjectedHeaderLine(), "the content type became a header line:\n" + receivedBody);
        assertTrue(receivedBody.contains("Content-Type: application/octet-stream"),
                "expected the unusable content type replaced; got:\n" + receivedBody);
    }

    @Test
    @DisplayName("a content type with parameters is not sent as given")
    void contentTypeWithParametersIsReplaced() throws IOException {
        // Only a plain type/subtype is allowed through, so the quoted-string case above has no
        // sibling that slips past a narrower rule.
        upload("log.txt", "text/plain; charset=utf-8");

        assertTrue(receivedBody.contains("Content-Type: application/octet-stream"),
                "got:\n" + receivedBody);
    }

    @Test
    @DisplayName("no content type at all is sent as octet-stream")
    void nullContentTypeIsReplaced() throws IOException {
        // AttachmentData.fromBytes takes the content type from the caller and does not require one.
        upload("shot.png", null);

        assertTrue(receivedBody.contains("Content-Type: application/octet-stream"),
                "got:\n" + receivedBody);
    }

    @Test
    @DisplayName("an ordinary name and content type arrive unchanged")
    void ordinaryValuesAreUntouched() throws IOException {
        // The other direction, and the one that matters on every upload: an escaper that mangles
        // normal input is worse than what it replaces.
        upload("screenshot-01 (retry).png", "image/png");

        assertTrue(receivedBody.contains("filename=\"screenshot-01 (retry).png\""),
                "got:\n" + receivedBody);
        assertTrue(receivedBody.contains("Content-Type: image/png"), "got:\n" + receivedBody);
    }

    @Test
    @DisplayName("the form fields still arrive")
    void formFieldsAreStillSent() throws IOException {
        upload("screenshot.png", "image/png");

        assertTrue(receivedBody.contains("name=\"runId\""), "got:\n" + receivedBody);
        assertTrue(receivedBody.contains("name=\"testId\""), "got:\n" + receivedBody);
        assertEquals(1, receivedBody.split("name=\"file\"", -1).length - 1,
                "expected exactly one file part; got:\n" + receivedBody);
    }
}
