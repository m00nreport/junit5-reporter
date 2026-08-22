package com.m00nreport.reporter;

import com.m00nreport.reporter.fixtures.TeardownFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A test body that returned is not a passed test.
 *
 * <p>The interceptor reported "passed" the moment the method returned, which is before any
 * teardown runs. An @AfterEach that throws - a failing cleanup assertion, a leaked-resource
 * check, a verify() in teardown - fails the test as far as JUnit and the exit code are concerned,
 * and the dashboard had already recorded a pass. A red build beside a green dashboard is the
 * worst disagreement the two can have, and it corrupts pass rate and flakiness on top of it.</p>
 *
 * <p>Driven through the real JUnit launcher rather than by calling the extension's methods,
 * because the ordering between the interceptor, @AfterEach and the TestWatcher callbacks IS the
 * defect: a test that called them in an order of its own choosing would prove nothing.</p>
 */
class TeardownOutcomeTest {

    private static HttpServer server;
    private static final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private static String baseUrl;

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
        M00NReporter.initialize(M00NConfig.builder()
                .enabled(true)
                .serverUrl(baseUrl)
                .apiKey("m00n_test_key")
                .launch("teardown outcome")
                .maxRetries(1)
                .build());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        M00NReporter.resetForTesting();
    }

    /** Runs one nested test class through the real JUnit engine, with our extension active. */
    private void run(Class<?> testClass) {
        M00NReporter.getInstance().startRun(1);
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(testClass))
                .build();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(request);
        M00NReporter.getInstance().endRun("finished");
    }

    /** The status field of the one recorded test/complete body. */
    private String reportedStatus() {
        List<String> completes = new ArrayList<>();
        synchronized (paths) {
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).endsWith("/test/complete")) {
                    completes.add(bodies.get(i));
                }
            }
        }
        assertEquals(1, completes.size(), "expected exactly one result, got: " + paths);
        String body = completes.get(0);
        int at = body.indexOf("\"status\"");
        if (at < 0) {
            return fail("no status in the reported result: " + body);
        }
        int open = body.indexOf('"', body.indexOf(':', at)) + 1;
        return body.substring(open, body.indexOf('"', open));
    }

    // ------------------------------------------------------------------------ the tests

    @Test
    @DisplayName("a failing @AfterEach makes the test failed, not passed")
    void aFailingTeardownMakesTheTestFailed() {
        run(TeardownFixtures.PassingBodyFailingTeardown.class);

        assertEquals("failed", reportedStatus(),
                "JUnit failed this test and the exit code says so; the dashboard must agree");
    }

    @Test
    @DisplayName("a clean run is still reported as passed")
    void aCleanRunIsStillPassed() {
        // The other direction, and the one that matters more: deferring the pass must not lose it.
        run(TeardownFixtures.EverythingPasses.class);

        assertEquals("passed", reportedStatus());
    }

    @Test
    @DisplayName("a failing body is still reported once, from the interceptor")
    void aFailingBodyIsStillReportedOnce() {
        // The failure path must keep reporting inside the interceptor: a retry extension catches
        // the throw one frame up, and after that this attempt no longer exists to report.
        run(TeardownFixtures.FailingBody.class);

        assertEquals("failed", reportedStatus());
    }
}
