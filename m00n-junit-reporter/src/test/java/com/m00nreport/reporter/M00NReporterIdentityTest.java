package com.m00nreport.reporter;

import com.m00nreport.reporter.model.AttachmentData;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which test a result belongs to, and whether its artifacts arrive before the run closes.
 *
 * <p>Two defects, both of which produce a dashboard that is confidently wrong rather than
 * obviously broken.</p>
 *
 * <p>The first is identity. activeTests was keyed on the display name, and display names are not
 * unique - "returns 200" and "renders" live in half the classes of any suite, and a
 * parameterized test repeats one name across every invocation. The second start overwrote the
 * first, so one test's result was reported against the other's id, carrying its timings, its
 * steps and its attachments, and the other was never completed at all. The README advertises
 * parallel safety.</p>
 *
 * <p>The second is timing. Playwright artifacts were submitted to the upload executor and never
 * registered, so waitForPendingUploads - which run/end calls precisely to avoid this - had
 * nothing to wait for. A multi-megabyte trace loses that race almost every time, and the 409 that
 * follows is a permanent code, so it is not retried.</p>
 */
class M00NReporterIdentityTest {

    private HttpServer server;
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        M00NReporter.resetForTesting();
        paths.clear();
        bodies.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(request, StandardCharsets.UTF_8));
            String response = exchange.getRequestURI().getPath().endsWith("/run/start")
                    ? "{\"runId\":\"run-mock\"}"
                    : "{\"ok\":true}";
            byte[] payload = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        M00NReporter.resetForTesting();
    }

    private M00NReporter reporter() {
        return M00NReporter.initialize(M00NConfig.builder()
                .enabled(true)
                .serverUrl(baseUrl)
                .apiKey("m00n_test_key")
                .launch("identity test")
                .maxRetries(1)
                .build());
    }

    private long countOf(String suffix) {
        synchronized (paths) {
            return paths.stream().filter(path -> path.endsWith(suffix)).count();
        }
    }

    private List<String> bodiesFor(String suffix) {
        List<String> found = new ArrayList<>();
        synchronized (paths) {
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).endsWith(suffix)) {
                    found.add(bodies.get(i));
                }
            }
        }
        return found;
    }

    /** The value of the testId field in a recorded body. */
    private static String testIdOf(String body) {
        int at = body.indexOf("\"testId\"");
        if (at < 0) {
            return null;
        }
        int open = body.indexOf('"', body.indexOf(':', at)) + 1;
        return body.substring(open, body.indexOf('"', open));
    }

    private static Set<String> setOf(String first, String second) {
        return new HashSet<>(List.of(first, second));
    }

    @Test
    @DisplayName("two tests sharing a display name are two tests")
    void twoTestsSharingADisplayNameAreTwoTests() {
        M00NReporter reporter = reporter();
        reporter.startRun(2);

        reporter.startTest("Checkout", "renders", "com.example.CheckoutTest", 0, "uid-checkout-1");
        reporter.startTest("Profile", "renders", "com.example.ProfileTest", 0, "uid-profile-1");
        reporter.completeTest("passed", null, "uid-checkout-1");
        reporter.completeTest("failed", new IllegalStateException("nope"), "uid-profile-1");
        reporter.endRun("finished");

        assertEquals(2, countOf("/test/start"), "both tests must start");
        assertEquals(2, countOf("/test/complete"), "and both must complete: " + paths);

        List<String> completes = bodiesFor("/test/complete");
        assertNotEquals(testIdOf(completes.get(0)), testIdOf(completes.get(1)),
                "each result must carry its own test's id");
        List<String> starts = bodiesFor("/test/start");
        assertEquals(
                setOf(testIdOf(starts.get(0)), testIdOf(starts.get(1))),
                setOf(testIdOf(completes.get(0)), testIdOf(completes.get(1))),
                "the ids that started must be the ids that completed");
    }

    @Test
    @DisplayName("the same name repeated in one class is still one test per invocation")
    void theSameNameRepeatedInOneClassIsStillOneTestPerInvocation() {
        // A parameterized test whose display name omits the argument - which is what a custom
        // display name produces - looks exactly like this.
        M00NReporter reporter = reporter();
        reporter.startRun(3);

        for (int i = 1; i <= 3; i++) {
            String uid = "uid-param-" + i;
            reporter.startTest("Prices", "converts correctly", "com.example.PriceTest", 0, uid);
            reporter.completeTest("passed", null, uid);
        }
        reporter.endRun("finished");

        assertEquals(3, countOf("/test/complete"));
    }

    @Test
    @DisplayName("an artifact is uploaded before the run is closed")
    void anArtifactIsUploadedBeforeTheRunIsClosed() {
        // The trace for the last failing test is the artifact anyone actually opens, and it was
        // the one most likely to lose this race.
        M00NReporter reporter = reporter();
        reporter.startRun(1);
        reporter.startTest("Checkout", "pays", "com.example.CheckoutTest", 0, "uid-1");
        reporter.completeTest("failed", new IllegalStateException("nope"), "uid-1");

        reporter.attachToTest("some-test-id",
                AttachmentData.screenshot("failure-screenshot.png", new byte[64 * 1024]));
        reporter.endRun("failed");

        int uploadAt = -1;
        int runEndAt = -1;
        synchronized (paths) {
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).endsWith("/attachment/upload")) {
                    uploadAt = i;
                }
                if (paths.get(i).endsWith("/run/end")) {
                    runEndAt = i;
                }
            }
        }
        assertTrue(uploadAt >= 0, "the attachment must reach the server at all: " + paths);
        assertTrue(runEndAt >= 0, "and the run must be closed: " + paths);
        assertTrue(uploadAt < runEndAt,
                "run/end must not overtake the upload it is supposed to wait for: " + paths);
    }
}
