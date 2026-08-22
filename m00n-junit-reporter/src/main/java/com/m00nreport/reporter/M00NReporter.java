package com.m00nreport.reporter;

import com.google.gson.JsonObject;
import com.m00nreport.reporter.model.AttachmentData;
import com.m00nreport.reporter.model.StepData;
import com.m00nreport.reporter.model.TestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M00N Reporter - Sends test results to M00N Report dashboard.
 * 
 * This reporter integrates with JUnit 5 via the M00NExtension.
 * It handles:
 * - Run lifecycle (start/end)
 * - Test lifecycle (start/complete)
 * - Step reporting (with real-time streaming)
 * - Attachment uploads (screenshots, videos, traces)
 * 
 * Thread-safe for parallel test execution.
 */
public class M00NReporter {
    
    private static final Logger log = LoggerFactory.getLogger(M00NReporter.class);
    
    // Singleton instance
    private static volatile M00NReporter instance;
    private static final Object lock = new Object();
    
    private final M00NConfig config;
    private final M00NHttpClient httpClient;
    private final boolean enabled;

    // Batches per-step events into size-or-time bounded HTTP requests.
    // Recreated per run because shutdown stops the scheduler thread.
    private StepBatcher stepBatcher;
    
    // Run state
    private volatile String runId;
    private volatile boolean runStarted = false;
    private volatile boolean runEnded = false;
    
    // Test tracking (thread-safe)
    private final ConcurrentMap<String, TestResult> activeTests = new ConcurrentHashMap<>();
    private final AtomicInteger totalTests = new AtomicInteger(0);
    private final AtomicInteger completedTests = new AtomicInteger(0);
    
    // Async upload executor (recreated on reset)
    private ExecutorService uploadExecutor;
    private final List<Future<?>> pendingUploads = Collections.synchronizedList(new ArrayList<>());
    
    // Statistics
    private final AtomicInteger testsStarted = new AtomicInteger(0);  // Actual test executions
    private final AtomicInteger testsReported = new AtomicInteger(0);
    private final AtomicInteger testsPassed = new AtomicInteger(0);
    private final AtomicInteger testsFailed = new AtomicInteger(0);
    private final AtomicInteger testsSkipped = new AtomicInteger(0);
    private final AtomicInteger testsRetried = new AtomicInteger(0);  // Tests with retry > 0
    private final AtomicInteger attachmentsUploaded = new AtomicInteger(0);
    private final AtomicLong totalDuration = new AtomicLong(0);
    private final AtomicInteger errors = new AtomicInteger(0);
    private volatile long runStartTime;
    
    private M00NReporter(M00NConfig config) {
        this.config = config;
        this.enabled = config.isEnabled();
        
        if (enabled) {
            this.httpClient = new M00NHttpClient(config);
            this.uploadExecutor = Executors.newFixedThreadPool(4);
            this.stepBatcher = createStepBatcher();
            log.info("[M00NReporter] Initialized - server: {}", config.getServerUrl());
        } else {
            this.httpClient = null;
            this.uploadExecutor = null;
            this.stepBatcher = null;
            log.info("[M00NReporter] Disabled - no serverUrl or apiKey configured");
        }
    }
    
    /**
     * Get or create the singleton reporter instance.
     */
    public static M00NReporter getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    M00NConfig config = M00NConfig.load();
                    instance = new M00NReporter(config);
                }
            }
        }
        return instance;
    }
    
    /**
     * Initialize with custom configuration.
     */
    public static M00NReporter initialize(M00NConfig config) {
        synchronized (lock) {
            if (instance != null && instance.runStarted) {
                log.warn("[M00NReporter] Cannot reinitialize - run already started");
                return instance;
            }
            instance = new M00NReporter(config);
            return instance;
        }
    }
    
    /**
     * Test seam: drop the singleton so the next initialize() builds a fresh one.
     *
     * <p>initialize() refuses to replace a reporter whose run has started, so without this each
     * test in a suite inherits the previous one's instance and its server.</p>
     */
    static void resetForTesting() {
        synchronized (lock) {
            instance = null;
        }
    }

    /**
     * Check if reporter is enabled and ready.
     */
    public boolean isEnabled() {
        return enabled && httpClient != null;
    }
    
    /**
     * Start a new test run.
     */
    public synchronized void startRun(int totalTestCount) {
        if (!enabled) return;
        
        // Auto-reset if previous run completed (supports multiple runs in same JVM)
        if (runEnded) {
            resetForNewRun();
        }
        
        if (runStarted) return;
        
        totalTests.set(totalTestCount);
        
        // Advisory only. run/start below is the call that decides whether reporting is
        // possible; the probe only chooses the wording once run/start has independently failed,
        // since not every deployment routes the probe path.
        boolean probeOk = httpClient.healthCheck();
        
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("launch", config.getLaunch());
            payload.put("tags", config.getTags());
            payload.put("total", totalTestCount);
            payload.put("startedAt", Instant.now().toString());
            
            // User-supplied attributes only (m00n.attribute.* from config). No framework or
            // language defaults are injected.
            payload.put("attributes", new HashMap<>(config.getAttributes()));
            
            JsonObject response = httpClient.post("/api/ingest/v2/run/start", payload);
            
            if (response.has("runId")) {
                runId = response.get("runId").getAsString();
                runStarted = true;
                runStartTime = System.currentTimeMillis();
                log.info("[M00NReporter] Run started: {}", runId);
            } else if (response.has("error")) {
                log.error("[M00NReporter] Failed to start run: {}{}",
                    response.get("error").getAsString(), probeOk ? "" : unreachableHint());
            }
        } catch (IOException e) {
            // The probe's only job: telling a user whose run/start failed whether the server is
            // unreachable or their key is wrong. Two very different next actions.
            log.error("[M00NReporter] Failed to start run: {}{}", e.getMessage(),
                probeOk ? "" : unreachableHint());
            errors.incrementAndGet();
        }
    }
    
    private String unreachableHint() {
        return " (the server at " + config.getServerUrl() + " did not answer a liveness probe either,"
            + " so it is likely unreachable rather than refusing this run)";
    }

    /**
     * End the current test run.
     */
    public synchronized void endRun(String status) {
        if (!enabled || !runStarted || runEnded) return;
        
        runEnded = true;

        // Drain any buffered step events before signalling run end
        if (stepBatcher != null) {
            int pending = stepBatcher.getPending();
            if (pending > 0) {
                log.debug("[M00NReporter] Flushing {} buffered step events", pending);
            }
            stepBatcher.flush();
        }

        // Wait for pending uploads
        waitForPendingUploads();
        
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("runId", runId);
            payload.put("status", status);
            payload.put("endedAt", Instant.now().toString());
            
            httpClient.post("/api/ingest/v2/run/end", payload);
            log.info("[M00NReporter] Run ended: {} (status: {})", runId, status);
        } catch (IOException e) {
            log.error("[M00NReporter] Failed to end run: {}", e.getMessage());
            errors.incrementAndGet();
        } finally {
            // In the finally block: the summary is the user's only confirmation that reporting
            // ran, so it must print even when the closing POST fails.
            printSummary();
            shutdown();
        }
    }
    
    /**
     * Reset state for a new run (supports multiple runs in same JVM).
     * Called automatically by startRun() when previous run has ended.
     */
    private void resetForNewRun() {
        runStarted = false;
        runEnded = false;
        runId = null;
        activeTests.clear();
        pendingUploads.clear();
        
        // Reset all counters
        totalTests.set(0);
        completedTests.set(0);
        testsStarted.set(0);
        testsReported.set(0);
        testsPassed.set(0);
        testsFailed.set(0);
        testsSkipped.set(0);
        testsRetried.set(0);
        attachmentsUploaded.set(0);
        totalDuration.set(0);
        errors.set(0);
        
        // Recreate executor if it was shut down
        if (uploadExecutor == null || uploadExecutor.isShutdown()) {
            uploadExecutor = Executors.newFixedThreadPool(4);
        }

        // Recreate step batcher (its scheduler was shut down in previous endRun)
        if (stepBatcher == null || stepBatcher.isShutdown()) {
            stepBatcher = createStepBatcher();
        }

        log.debug("[M00NReporter] Reset for new run");
    }

    /**
     * Step batcher tuned to match the JS Playwright reporter:
     * flush every 200 ms or when 100 step events accumulate, whichever comes first.
     */
    private StepBatcher createStepBatcher() {
        return new StepBatcher(httpClient, "/api/ingest/v2/steps/stream", 200, 100);
    }
    
    /**
     * Start tracking a test under an explicit key.
     *
     * <p>The key pairs a result with the start that opened it, and display names are not unique:
     * a name like "renders" repeats across classes, and a {@code @ParameterizedTest} repeats one
     * name across every invocation. The extension passes JUnit's own uniqueId, which is unique
     * per invocation and per retry.</p>
     *
     * @param suiteDisplayName Display name for the test suite/class
     * @param testDisplayName Display name for the test method
     * @param actualClassName Full class name for file path (e.g., com.example.MyTest)
     * @param retry Retry attempt number (0 for first run, 1 for first retry, etc.)
     * @param key Key that pairs this start with its completeTest call
     */
    public TestResult startTest(String suiteDisplayName, String testDisplayName, String actualClassName,
                                int retry, String key) {
        if (!enabled || !runStarted) return null;
        
        testsStarted.incrementAndGet(); // Count actual test executions
        if (retry > 0) {
            testsRetried.incrementAndGet(); // Count retry attempts
        }
        
        TestResult result = new TestResult();
        result.start(runId, suiteDisplayName, testDisplayName, actualClassName);
        result.setRetry(retry); // Set retry BEFORE sending to API
        
        activeTests.put(key, result);
        
        // Fire-and-forget test start
        Map<String, Object> payload = new HashMap<>();
        payload.put("runId", runId);
        payload.put("testId", result.getTestId());
        payload.put("titlePath", result.getTitlePath());
        payload.put("filePath", result.getFilePath());
        payload.put("retry", result.getRetry()); // Now contains correct retry value
        payload.put("startedAt", result.getStartedAt());
        
        httpClient.postAsync("/api/ingest/v2/test/start", payload);
        
        log.debug("[M00NReporter] Test started: {} (retry={})", key, retry);
        
        return result;
    }
    
    /**
     * Complete the test opened under this key and report its result. See
     * {@link #startTest(String, String, String, int, String)} for why the key is not the name.
     */
    public void completeTest(String status, Throwable error, String key) {
        if (!enabled || !runStarted) return;
        
        TestResult result = activeTests.remove(key);
        
        if (result == null) {
            log.warn("[M00NReporter] No active test found for: {}", key);
            return;
        }
        
        result.end(status, error);
        completedTests.incrementAndGet();
        
        // Track statistics
        switch (status) {
            case "passed" -> testsPassed.incrementAndGet();
            case "failed" -> testsFailed.incrementAndGet();
            case "skipped" -> testsSkipped.incrementAndGet();
        }
        totalDuration.addAndGet(result.getDuration());
        
        try {
            // Send test completion
            Map<String, Object> payload = new HashMap<>();
            payload.put("testId", result.getTestId());
            payload.put("runId", runId);
            payload.put("filePath", result.getFilePath());
            payload.put("titlePath", result.getTitlePath());
            payload.put("retry", result.getRetry());
            payload.put("startedAt", result.getStartedAt());
            payload.put("endedAt", result.getEndedAt());
            payload.put("status", result.getStatus());
            payload.put("duration", result.getDuration());
            
            if (result.getError() != null) {
                Map<String, String> errorMap = new HashMap<>();
                errorMap.put("message", result.getError().getMessage());
                errorMap.put("stack", result.getError().getStack());
                errorMap.put("name", result.getError().getName());
                payload.put("error", errorMap);
            }
            
            // Convert steps to list of maps
            List<Map<String, Object>> stepMaps = new ArrayList<>();
            for (var step : result.getSteps()) {
                Map<String, Object> stepMap = new HashMap<>();
                stepMap.put("title", step.getTitle());
                stepMap.put("category", step.getCategory());
                stepMap.put("status", step.getStatus());
                stepMap.put("duration", step.getDuration());
                stepMap.put("nestingLevel", step.getNestingLevel());
                stepMap.put("index", step.getIndex());
                if (step.getError() != null) {
                    Map<String, String> stepErrorMap = new HashMap<>();
                    stepErrorMap.put("message", step.getError().getMessage());
                    stepErrorMap.put("stack", step.getError().getStack());
                    stepMap.put("error", stepErrorMap);
                }
                stepMaps.add(stepMap);
            }
            payload.put("steps", stepMaps);
            payload.put("attachments", Collections.emptyList());
            
            httpClient.post("/api/ingest/v2/test/complete", payload);
            testsReported.incrementAndGet();
            
            // Upload attachments asynchronously
            if (!result.getAttachments().isEmpty()) {
                uploadAttachmentsAsync(result);
            }
            
            log.debug("[M00NReporter] Test completed: {} ({})", key, status);
        } catch (IOException e) {
            log.error("[M00NReporter] Failed to complete test {}: {}", key, e.getMessage());
            errors.incrementAndGet();
        }
    }
    
    /**
     * The test opened under this key, for adding steps or attachments to it.
     *
     * <p>Keyed rather than named, for the reason in
     * {@link #startTest(String, String, String, int, String)}: two tests can share a display name
     * and a step filed under the name reaches whichever of them started last.</p>
     */
    public TestResult getActiveTest(String key) {
        return activeTests.get(key);
    }
    
    /**
     * Stream a step to the server in real-time.
     * This enables live step updates in the UI via WebSocket.
     * 
     * @param testResult The test this step belongs to
     * @param step The step data
     * @param action "append" for new step, "end" for completed step
     */
    public void streamStep(TestResult testResult, StepData step, String action) {
        if (!enabled || !runStarted || httpClient == null) return;
        
        try {
            Map<String, Object> stepItem = new HashMap<>();
            stepItem.put("runId", runId);
            stepItem.put("testId", testResult.getTestId());
            stepItem.put("title", step.getTitle());
            stepItem.put("category", step.getCategory());
            stepItem.put("status", step.getStatus());
            stepItem.put("duration", step.getDuration());
            stepItem.put("stepIndex", step.getIndex());
            stepItem.put("nestingLevel", step.getNestingLevel());
            stepItem.put("action", action);
            
            if (step.getError() != null) {
                Map<String, String> errorMap = new HashMap<>();
                errorMap.put("message", step.getError().getMessage());
                errorMap.put("stack", step.getError().getStack());
                stepItem.put("error", errorMap);
            }
            
            // Fire-and-forget - batcher coalesces with other in-flight steps
            // and POSTs every flushInterval ms (or sooner when maxBatchSize is reached).
            stepBatcher.add(stepItem);

            log.debug("[M00NReporter] Step queued: {} ({})", step.getTitle(), action);
        } catch (Exception e) {
            // Don't fail test for step streaming issues
            log.debug("[M00NReporter] Failed to stream step: {}", e.getMessage());
        }
    }
    
    /**
     * Attach an artifact to the current test.
     * 
     * @param attachment The attachment data (trace, video, screenshot, etc.)
     */
    public void attach(AttachmentData attachment) {
        if (!enabled || !runStarted || attachment == null) return;
        
        // Find the current test via thread-local
        TestResult currentTest = M00NStep.current().orElse(null);
        
        if (currentTest != null) {
            attachToTest(currentTest.getTestId(), attachment);
        } else {
            log.warn("[M00NReporter] No active test found for attachment: {}", attachment.getName());
        }
    }
    
    /**
     * Attach an artifact to a specific test by ID.
     * Use this for async attachment when the current test context may have changed.
     * 
     * @param testId The test ID to attach to
     * @param attachment The attachment data (trace, video, screenshot, etc.)
     */
    public void attachToTest(String testId, AttachmentData attachment) {
        if (!enabled || !runStarted || testId == null || attachment == null) return;
        
        log.info("[M00NReporter] Attaching {} ({} bytes) to test {}", 
            attachment.getName(), attachment.getSize(), testId);
        
        // The future is registered so waitForPendingUploads() at run end has something to wait
        // for. An unregistered upload races run/end, and the 409 RUN_NOT_ACTIVE that follows is a
        // permanent code, so it is not retried.
        Future<?> future = uploadExecutor.submit(() -> {
            try {
                httpClient.uploadAttachment(
                    "/api/ingest/v2/attachment/upload",
                    testId,
                    runId,
                    attachment
                );
                attachmentsUploaded.incrementAndGet();
            } catch (Exception e) {
                log.warn("[M00NReporter] Failed to upload attachment {}: {}", 
                    attachment.getName(), e.getMessage());
                errors.incrementAndGet();
            }
        });
        pendingUploads.add(future);
    }
    
    /**
     * Upload a single attachment asynchronously.
     */
    private void uploadAttachmentAsync(TestResult testResult, AttachmentData attachment) {
        if (httpClient == null) return;
        
        uploadExecutor.submit(() -> {
            try {
                httpClient.uploadAttachment(
                    "/api/ingest/v2/attachment/upload",
                    testResult.getTestId(),
                    runId,
                    attachment
                );
            } catch (Exception e) {
                log.warn("[M00NReporter] Failed to upload attachment {}: {}", 
                    attachment.getName(), e.getMessage());
            }
        });
    }
    
    /**
     * Upload attachments asynchronously.
     */
    private void uploadAttachmentsAsync(TestResult result) {
        Future<?> future = uploadExecutor.submit(() -> {
            for (AttachmentData attachment : result.getAttachments()) {
                try {
                    Map<String, String> fields = new HashMap<>();
                    fields.put("runId", runId);
                    fields.put("testId", result.getTestId());
                    
                    if (attachment.isFileAttachment()) {
                        httpClient.uploadFile(
                            "/api/ingest/v2/attachment/upload",
                            fields,
                            attachment.getFile(),
                            attachment.getName(),
                            attachment.getContentType()
                        );
                    } else {
                        httpClient.uploadBytes(
                            "/api/ingest/v2/attachment/upload",
                            fields,
                            attachment.getData(),
                            attachment.getName(),
                            attachment.getContentType()
                        );
                    }
                    
                    attachmentsUploaded.incrementAndGet();
                    log.debug("[M00NReporter] Uploaded attachment: {}", attachment.getName());
                } catch (IOException e) {
                    log.warn("[M00NReporter] Failed to upload attachment {}: {}", 
                        attachment.getName(), e.getMessage());
                    errors.incrementAndGet();
                }
            }
        });
        pendingUploads.add(future);
    }
    
    /**
     * Wait for all pending attachment uploads.
     */
    private void waitForPendingUploads() {
        if (pendingUploads.isEmpty()) return;
        
        log.info("[M00NReporter] Waiting for {} pending uploads...", pendingUploads.size());
        
        for (Future<?> future : pendingUploads) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[M00NReporter] Upload wait failed: {}", e.getMessage());
            }
        }
        pendingUploads.clear();
    }
    
    /**
     * Shutdown the reporter.
     */
    private void shutdown() {
        if (stepBatcher != null) {
            stepBatcher.shutdownGracefully();
        }

        if (uploadExecutor != null) {
            uploadExecutor.shutdown();
            try {
                if (!uploadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    uploadExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Note: Don't shutdown httpClient - OkHttp clients are designed to be
        // long-lived and reused across runs. The connection pool will naturally
        // expire idle connections after the configured keep-alive time.
    }
    
    /**
     * Print run summary.
     */
    private void printSummary() {
        long runDuration = System.currentTimeMillis() - runStartTime;
        int passed = testsPassed.get();
        int failed = testsFailed.get();
        int skipped = testsSkipped.get();
        int total = testsStarted.get();
        int retries = testsRetried.get();
        int attachments = attachmentsUploaded.get();
        
        // Calculate pass rate (excluding skipped)
        int executed = passed + failed;
        double passRate = executed > 0 ? (passed * 100.0 / executed) : 0;
        
        System.out.println();
        System.out.println("═".repeat(64));
        System.out.println();
        System.out.println("  M00N REPORT - Launch Summary");
        System.out.println();
        System.out.println("─".repeat(64));
        System.out.println();
        System.out.printf("  📊  Total:     %d tests%n", total);
        System.out.printf("      Results:   %d passed  •  %d failed  •  %d skipped%n", 
            passed, failed, skipped);
        System.out.println();
        System.out.printf("  ✅  Pass Rate: %.1f%% (%d/%d executed)%n", passRate, passed, executed);
        System.out.println();
        
        if (retries > 0) {
            System.out.printf("  🔄  Retries:   %d test attempts were retries%n", retries);
            System.out.println();
        }
        
        if (attachments > 0) {
            System.out.printf("  📎  Artifacts: %d attachments uploaded%n", attachments);
            System.out.println();
        }
        
        System.out.printf("  ⏱️   Duration:  %s%n", formatDuration(runDuration));
        System.out.println();
        System.out.println("─".repeat(64));
        System.out.println();
        System.out.println("  🔗  Run ID:    " + runId);
        System.out.println("  📡  Server:    " + config.getServerUrl());
        System.out.println();
        
        if (errors.get() > 0) {
            System.out.printf("  ⚠️   Errors:    %d reporting errors occurred%n", errors.get());
            System.out.println();
        }
        
        System.out.println("═".repeat(64));
        System.out.println();
    }
    
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else if (seconds > 0) {
            return String.format("%ds", seconds);
        } else {
            return String.format("%dms", millis);
        }
    }
    
    
    public String getRunId() {
        return runId;
    }
    
    // Statistics getters for M00NTestExecutionListener
    public int getTestsPassed() {
        return testsPassed.get();
    }
    
    public int getTestsFailed() {
        return testsFailed.get();
    }
    
    public int getTestsSkipped() {
        return testsSkipped.get();
    }
}
