package com.m00nreport.reporter;

import com.m00nreport.reporter.model.AttachmentData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Integration point for Playwright tests with M00N Report.
 * 
 * <p>Enables automatic screenshot/trace capture on test failure.
 * Works with ANY custom base test class or extension - no inheritance required!</p>
 * 
 * <h2>Quick Setup (Screenshots):</h2>
 * <pre>{@code
 * // No @ExtendWith needed - M00NExtension is auto-registered!
 * class MyTests {
 *     @BeforeEach
 *     void setUp() {
 *         page = context.newPage();
 *         M00NPlaywright.setPage(page);  // That's it! Screenshots on failure
 *     }
 * }
 * }</pre>
 * 
 * <h2>With Traces:</h2>
 * <pre>{@code
 * class MyTests {
 *     @BeforeEach
 *     void setUp() {
 *         context = browser.newContext();
 *         context.tracing().start(new Tracing.StartOptions()
 *             .setScreenshots(true).setSnapshots(true));
 *         page = context.newPage();
 *         
 *         M00NPlaywright.setPage(page);
 *         M00NPlaywright.setContext(context);  // Enables trace capture
 *     }
 * }
 * }</pre>
 * 
 * <h2>With Videos (requires extension):</h2>
 * <pre>{@code
 * @ExtendWith(M00NPlaywrightExtension.class)  // Only for video capture
 * class MyTests {
 *     @BeforeEach
 *     void setUp() {
 *         context = browser.newContext(new Browser.NewContextOptions()
 *             .setRecordVideoDir(Paths.get("videos")));
 *         page = context.newPage();
 *         
 *         M00NPlaywright.setPage(page);
 *         M00NPlaywright.setContext(context);
 *     }
 *     
 *     @AfterEach
 *     void tearDown() {
 *         context.close();  // Video captured after close
 *     }
 * }
 * }</pre>
 * 
 * <h2>Works With Custom Extensions:</h2>
 * <p>Since artifacts are captured IMMEDIATELY when the test fails (before @AfterEach),
 * it works seamlessly with custom extensions that close the browser in teardown.</p>
 * 
 * @see M00NPlaywrightExtension
 */
public final class M00NPlaywright {
    
    private static final Logger log = LoggerFactory.getLogger(M00NPlaywright.class);
    
    // Configuration
    private static final int VIDEO_FINALIZE_TIMEOUT_MS = 10000;  // 10 seconds for video to finalize
    private static final int VIDEO_POLL_INTERVAL_MS = 100;
    
    // Thread-local storage for Playwright objects
    private static final ThreadLocal<Object> CURRENT_PAGE = new ThreadLocal<>();
    private static final ThreadLocal<Object> CURRENT_CONTEXT = new ThreadLocal<>();
    private static final ThreadLocal<Path> TRACE_PATH = new ThreadLocal<>();
    private static final ThreadLocal<Supplier<Path>> VIDEO_PATH_SUPPLIER = new ThreadLocal<>();
    
    // Track if artifacts were already captured (prevents duplicates)
    private static final ThreadLocal<Boolean> SCREENSHOT_CAPTURED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> TRACE_CAPTURED = ThreadLocal.withInitial(() -> false);
    
    // Video capture state (for custom extensions)
    // Store the Video object itself for reliable saveAs() call after close
    private static final ThreadLocal<Object> SAVED_VIDEO_OBJECT = new ThreadLocal<>();
    private static final ThreadLocal<String> SAVED_TEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<Path> SAVED_VIDEO_DIR = new ThreadLocal<>();
    
    private M00NPlaywright() {} // Utility class
    
    // =========================================================================
    // Registration API - Called by user's test code
    // =========================================================================
    
    /**
     * Register the current Playwright Page for automatic screenshot capture.
     * Call this in your @BeforeEach after creating the page.
     * 
     * @param page The Playwright Page object (com.microsoft.playwright.Page)
     */
    public static void setPage(Object page) {
        CURRENT_PAGE.set(page);
    }
    
    /**
     * Register the current BrowserContext for trace capture.
     * Call this if you want traces to be captured on failure.
     * 
     * @param context The Playwright BrowserContext (com.microsoft.playwright.BrowserContext)
     */
    public static void setContext(Object context) {
        CURRENT_CONTEXT.set(context);
    }
    
    /**
     * Set the path where trace should be saved.
     * If not set, a default path will be used.
     * 
     * @param path Path for trace file (should end with .zip)
     */
    public static void setTracePath(Path path) {
        TRACE_PATH.set(path);
    }
    
    /**
     * Set a supplier that returns the video path after context is closed.
     * This is needed because video path is only available after page.video().path().
     * 
     * @param supplier Supplier that returns the video path
     */
    public static void setVideoPathSupplier(Supplier<Path> supplier) {
        VIDEO_PATH_SUPPLIER.set(supplier);
    }
    
    /**
     * Clear all registrations. Called automatically by M00NExtension.
     * Note: Video path is preserved for capture after context close.
     */
    public static void clear() {
        CURRENT_PAGE.remove();
        CURRENT_CONTEXT.remove();
        TRACE_PATH.remove();
        VIDEO_PATH_SUPPLIER.remove();
        SCREENSHOT_CAPTURED.remove();
        TRACE_CAPTURED.remove();
        // Note: SAVED_VIDEO_OBJECT, SAVED_TEST_ID, SAVED_VIDEO_DIR are NOT cleared here
        // They are cleared in captureVideoAfterClose() after video is captured
    }
    
    // =========================================================================
    // Video Capture API - For custom Playwright extensions
    // =========================================================================
    
    /**
     * Prepare video capture by saving the Video object BEFORE closing page/context.
     * 
     * <p>Call this in your custom extension's afterEach(), BEFORE closing the page.
     * The video file is only finalized when the context is closed, but we need
     * to save the Video object reference while the page is still valid.</p>
     * 
     * <h2>Usage in Custom Extension:</h2>
     * <pre>{@code
     * @Override
     * public void afterEach(ExtensionContext context) {
     *     // BEFORE closing:
     *     M00NPlaywright.prepareVideoCapture(page);
     *     
     *     // Close resources
     *     page.close();
     *     browserContext.close();
     *     
     *     // AFTER closing:
     *     M00NPlaywright.captureVideoAfterClose();
     * }
     * }</pre>
     * 
     * @param page The Playwright Page object (must have video recording enabled)
     */
    public static void prepareVideoCapture(Object page) {
        if (page == null) {
            log.debug("[M00NPlaywright] prepareVideoCapture: page is null");
            return;
        }
        
        // Only prepare if test failed
        if (!M00NStep.isCurrentTestFailed()) {
            log.debug("[M00NPlaywright] prepareVideoCapture: test did not fail, skipping");
            return;
        }
        
        try {
            // Get Video object using reflection
            var pageClass = Class.forName("com.microsoft.playwright.Page");
            var videoMethod = pageClass.getMethod("video");
            Object video = videoMethod.invoke(page);
            
            if (video == null) {
                log.debug("[M00NPlaywright] prepareVideoCapture: page.video() returned null - video recording not enabled?");
                return;
            }
            
            // Save the Video object for later (to call saveAs after context close)
            SAVED_VIDEO_OBJECT.set(video);
            
            // Also save the video directory for fallback path resolution
            var videoClass = Class.forName("com.microsoft.playwright.Video");
            var pathMethod = videoClass.getMethod("path");
            Path videoPath = (Path) pathMethod.invoke(video);
            if (videoPath != null && videoPath.getParent() != null) {
                SAVED_VIDEO_DIR.set(videoPath.getParent());
            }
            
            // Save test ID for later
            String testId = M00NStep.current()
                .map(result -> {
                    try {
                        return (String) result.getClass().getMethod("getTestId").invoke(result);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .orElse(null);
            
            if (testId != null) {
                SAVED_TEST_ID.set(testId);
                log.debug("[M00NPlaywright] Video object saved for capture, testId: {}, path: {}", testId, videoPath);
            } else {
                log.warn("[M00NPlaywright] prepareVideoCapture: could not get testId from current test");
                // Clear video object since we can't upload without testId
                SAVED_VIDEO_OBJECT.remove();
                SAVED_VIDEO_DIR.remove();
            }
        } catch (ClassNotFoundException e) {
            log.debug("[M00NPlaywright] Playwright not on classpath: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[M00NPlaywright] prepareVideoCapture failed: {}", e.getMessage());
        }
    }
    
    /**
     * Capture video AFTER context is closed and upload to M00N Reporter.
     * 
     * <p>Call this in your custom extension's afterEach(), AFTER closing the context.
     * Uses Playwright's Video.saveAs() which properly blocks until video is finalized.</p>
     * 
     * <p>Must be paired with {@link #prepareVideoCapture(Object)} called before closing.</p>
     * 
     * @return true if video was captured and uploaded successfully
     */
    public static boolean captureVideoAfterClose() {
        Object video = SAVED_VIDEO_OBJECT.get();
        String testId = SAVED_TEST_ID.get();
        Path videoDir = SAVED_VIDEO_DIR.get();
        
        // Clear saved state
        SAVED_VIDEO_OBJECT.remove();
        SAVED_TEST_ID.remove();
        SAVED_VIDEO_DIR.remove();
        
        if (video == null || testId == null) {
            log.debug("[M00NPlaywright] captureVideoAfterClose: no video ({}) or testId ({}) saved", 
                video != null ? "present" : "null", 
                testId != null ? testId : "null");
            return false;
        }
        
        try {
            // Use Video.saveAs() - this blocks until video is fully written
            var videoClass = Class.forName("com.microsoft.playwright.Video");
            var saveAsMethod = videoClass.getMethod("saveAs", Path.class);
            
            // Create temp file for video
            Path targetDir = videoDir != null ? videoDir : Path.of(System.getProperty("java.io.tmpdir"));
            Path targetPath = targetDir.resolve("m00n-video-" + System.currentTimeMillis() + ".webm");
            
            log.debug("[M00NPlaywright] Calling Video.saveAs({})...", targetPath);
            
            // This call blocks until video is finalized - no polling needed!
            saveAsMethod.invoke(video, targetPath);
            
            log.debug("[M00NPlaywright] Video.saveAs() completed");
            
            // Verify file exists and has content
            if (!Files.exists(targetPath)) {
                log.warn("[M00NPlaywright] Video.saveAs() completed but file doesn't exist: {}", targetPath);
                return false;
            }
            
            long fileSize = Files.size(targetPath);
            if (fileSize == 0) {
                log.warn("[M00NPlaywright] Video file is empty: {}", targetPath);
                Files.deleteIfExists(targetPath);
                return false;
            }
            
            // Check size limit before reading into memory
            if (fileSize > AttachmentData.MAX_ATTACHMENT_SIZE) {
                log.warn("[M00NPlaywright] Video skipped: \"{}\" ({} MB) exceeds {} MB limit",
                    targetPath.getFileName(), String.format("%.1f", fileSize / (1024.0 * 1024.0)),
                    AttachmentData.MAX_ATTACHMENT_SIZE / (1024 * 1024));
                Files.deleteIfExists(targetPath);
                return false;
            }
            
            // Read and upload
            byte[] videoBytes = Files.readAllBytes(targetPath);
            String filename = targetPath.getFileName().toString();
            
            AttachmentData attachment = AttachmentData.video(filename, videoBytes);
            if (attachment == null) {
                Files.deleteIfExists(targetPath);
                return false;
            }
            M00NReporter.getInstance().attachToTest(testId, attachment);
            
            log.info("[M00NPlaywright] ✓ Video captured via saveAs ({} KB)", videoBytes.length / 1024);
            
            // Clean up temp file
            try {
                Files.deleteIfExists(targetPath);
            } catch (Exception e) {
                log.debug("[M00NPlaywright] Could not delete temp video file: {}", e.getMessage());
            }
            
            return true;
            
        } catch (Exception e) {
            String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            log.warn("[M00NPlaywright] Video capture failed: {}", errorMsg);
            
            // Log more details for debugging
            if (log.isDebugEnabled()) {
                log.debug("[M00NPlaywright] Video capture exception details", e);
            }
            
            return false;
        }
    }
    
    // =========================================================================
    // Internal API - Used by M00NExtension
    // =========================================================================
    
    /**
     * Check if a page is registered.
     */
    static boolean hasPage() {
        return CURRENT_PAGE.get() != null;
    }
    
    /**
     * Check if a context is registered.
     */
    static boolean hasContext() {
        return CURRENT_CONTEXT.get() != null;
    }
    
    /**
     * Check if a screenshot was already captured for this test.
     * Useful for base test classes to avoid duplicate screenshots.
     * 
     * @return true if screenshot was already captured
     */
    public static boolean isScreenshotCaptured() {
        return SCREENSHOT_CAPTURED.get();
    }
    
    /**
     * Check if a trace was already captured for this test.
     * Useful for base test classes to avoid duplicate traces.
     * 
     * @return true if trace was already captured
     */
    public static boolean isTraceCaptured() {
        return TRACE_CAPTURED.get();
    }
    
    /**
     * Mark screenshot as captured (call this if you capture screenshots manually).
     * Prevents M00NExtension from capturing duplicate screenshots.
     */
    public static void markScreenshotCaptured() {
        SCREENSHOT_CAPTURED.set(true);
    }
    
    /**
     * Mark trace as captured (call this if you capture traces manually).
     * Prevents M00NExtension from capturing duplicate traces.
     */
    public static void markTraceCaptured() {
        TRACE_CAPTURED.set(true);
    }
    
    /**
     * Capture screenshot from registered page.
     * Uses reflection to avoid compile-time dependency on Playwright.
     * 
     * @param testId The test ID to attach screenshot to
     * @return true if screenshot was captured successfully
     */
    static boolean captureScreenshot(String testId) {
        // Prevent duplicate screenshots
        if (SCREENSHOT_CAPTURED.get()) {
            log.debug("[M00NPlaywright] Screenshot already captured for this test, skipping");
            return true;
        }
        
        Object page = CURRENT_PAGE.get();
        if (page == null) return false;
        
        try {
            // Use reflection to call page.screenshot()
            // This avoids requiring Playwright as a compile dependency
            // IMPORTANT: Use interface classes, NOT implementation classes (PageImpl)
            // to avoid Java module access restrictions
            var screenshotOptionsClass = Class.forName("com.microsoft.playwright.Page$ScreenshotOptions");
            var screenshotOptions = screenshotOptionsClass.getDeclaredConstructor().newInstance();
            
            // Set fullPage = true
            var setFullPageMethod = screenshotOptionsClass.getMethod("setFullPage", boolean.class);
            setFullPageMethod.invoke(screenshotOptions, true);
            
            // Call page.screenshot(options) using Page interface
            var pageClass = Class.forName("com.microsoft.playwright.Page");
            var screenshotMethod = pageClass.getMethod("screenshot", screenshotOptionsClass);
            byte[] screenshot = (byte[]) screenshotMethod.invoke(page, screenshotOptions);
            
            if (screenshot != null && screenshot.length > 0) {
                M00NReporter.getInstance().attachToTest(testId,
                    AttachmentData.screenshot("failure-screenshot.png", screenshot));
                log.info("[M00NPlaywright] ✓ Screenshot captured ({} KB)", screenshot.length / 1024);
                SCREENSHOT_CAPTURED.set(true);
                return true;
            }
        } catch (Exception e) {
            log.error("[M00NPlaywright] ✗ Screenshot failed: {}", e.getMessage());
        }
        return false;
    }
    
    /**
     * Capture trace from registered context.
     * 
     * @param testId The test ID to attach trace to
     * @param testName Sanitized test name for file naming
     * @return true if trace was captured successfully
     */
    static boolean captureTrace(String testId, String testName) {
        // Prevent duplicate traces
        if (TRACE_CAPTURED.get()) {
            log.debug("[M00NPlaywright] Trace already captured for this test, skipping");
            return true;
        }
        
        Object context = CURRENT_CONTEXT.get();
        if (context == null) return false;
        
        try {
            // Get or create trace path
            Path tracePath = TRACE_PATH.get();
            if (tracePath == null) {
                tracePath = Path.of("test-results", "traces", testName + "-trace.zip");
                Files.createDirectories(tracePath.getParent());
            }
            
            // Use reflection to call context.tracing().stop(options)
            // IMPORTANT: Use interface classes, NOT implementation classes (BrowserContextImpl)
            // to avoid Java module access restrictions
            var browserContextClass = Class.forName("com.microsoft.playwright.BrowserContext");
            var tracingMethod = browserContextClass.getMethod("tracing");
            Object tracing = tracingMethod.invoke(context);
            
            var stopOptionsClass = Class.forName("com.microsoft.playwright.Tracing$StopOptions");
            var stopOptions = stopOptionsClass.getDeclaredConstructor().newInstance();
            var setPathMethod = stopOptionsClass.getMethod("setPath", Path.class);
            setPathMethod.invoke(stopOptions, tracePath);
            
            // Use Tracing interface, not TracingImpl
            var tracingClass = Class.forName("com.microsoft.playwright.Tracing");
            var stopMethod = tracingClass.getMethod("stop", stopOptionsClass);
            stopMethod.invoke(tracing, stopOptions);
            
            if (Files.exists(tracePath)) {
                long traceSize = Files.size(tracePath);
                if (traceSize > AttachmentData.MAX_ATTACHMENT_SIZE) {
                    log.warn("[M00NPlaywright] Trace skipped: \"{}\" ({} MB) exceeds {} MB limit",
                        tracePath.getFileName(), String.format("%.1f", traceSize / (1024.0 * 1024.0)),
                        AttachmentData.MAX_ATTACHMENT_SIZE / (1024 * 1024));
                    return false;
                }
                byte[] traceBytes = Files.readAllBytes(tracePath);
                AttachmentData attachment = AttachmentData.trace("trace.zip", traceBytes);
                if (attachment == null) return false;
                M00NReporter.getInstance().attachToTest(testId, attachment);
                log.info("[M00NPlaywright] ✓ Trace captured ({} KB)", traceBytes.length / 1024);
                TRACE_CAPTURED.set(true);
                return true;
            }
        } catch (Exception e) {
            // Tracing might not have been started - this is OK, don't log as error
            String msg = e.getMessage();
            if (msg == null || !msg.contains("Tracing has not been started")) {
                log.error("[M00NPlaywright] ✗ Trace failed: {}", msg);
            }
        }
        return false;
    }
    
    /**
     * Capture video from registered context.
     * Must be called AFTER context is closed.
     * 
     * @param testId The test ID to attach video to
     * @return true if video was captured successfully
     */
    static boolean captureVideo(String testId) {
        Supplier<Path> supplier = VIDEO_PATH_SUPPLIER.get();
        if (supplier == null) {
            // Try to get video path from page
            // IMPORTANT: Use interface classes to avoid Java module access restrictions
            Object page = CURRENT_PAGE.get();
            if (page != null) {
                try {
                    var pageClass = Class.forName("com.microsoft.playwright.Page");
                    var videoMethod = pageClass.getMethod("video");
                    Object video = videoMethod.invoke(page);
                    if (video != null) {
                        var videoClass = Class.forName("com.microsoft.playwright.Video");
                        var pathMethod = videoClass.getMethod("path");
                        Path videoPath = (Path) pathMethod.invoke(video);
                        if (videoPath != null) {
                            return captureVideoFromPath(testId, videoPath);
                        }
                    }
                } catch (Exception e) {
                    // Video might not be enabled - this is OK
                    log.debug("[M00NPlaywright] Video not available: {}", e.getMessage());
                }
            }
            return false;
        }
        
        try {
            Path videoPath = supplier.get();
            if (videoPath == null) {
                log.debug("[M00NPlaywright] Video path supplier returned null");
                return false;
            }
            return captureVideoFromPath(testId, videoPath);
        } catch (Exception e) {
            log.error("[M00NPlaywright] ✗ Video failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Capture video from path with polling for file readiness.
     * Videos may take a moment to finalize after context close.
     */
    private static boolean captureVideoFromPath(String testId, Path videoPath) {
        if (videoPath == null) {
            return false;
        }
        
        try {
            // Poll for video file to be ready (with timeout)
            long startTime = System.currentTimeMillis();
            long previousSize = -1;
            
            while (System.currentTimeMillis() - startTime < VIDEO_FINALIZE_TIMEOUT_MS) {
                if (!Files.exists(videoPath)) {
                    Thread.sleep(VIDEO_POLL_INTERVAL_MS);
                    continue;
                }
                
                long currentSize = Files.size(videoPath);
                
                // File exists and size is stable (same as last check) - it's ready
                if (currentSize > 0 && currentSize == previousSize) {
                    if (currentSize > AttachmentData.MAX_ATTACHMENT_SIZE) {
                        log.warn("[M00NPlaywright] Video skipped: \"{}\" ({} MB) exceeds {} MB limit",
                            extractVideoFilename(videoPath), String.format("%.1f", currentSize / (1024.0 * 1024.0)),
                            AttachmentData.MAX_ATTACHMENT_SIZE / (1024 * 1024));
                        return false;
                    }
                    byte[] videoBytes = Files.readAllBytes(videoPath);
                    String filename = extractVideoFilename(videoPath);
                    AttachmentData attachment = AttachmentData.video(filename, videoBytes);
                    if (attachment == null) return false;
                    M00NReporter.getInstance().attachToTest(testId, attachment);
                    log.info("[M00NPlaywright] ✓ Video captured ({} KB)", videoBytes.length / 1024);
                    return true;
                }
                
                previousSize = currentSize;
                Thread.sleep(VIDEO_POLL_INTERVAL_MS);
            }
            
            // Timeout reached - try to read anyway if file exists
            if (Files.exists(videoPath) && Files.size(videoPath) > 0) {
                long fallbackSize = Files.size(videoPath);
                if (fallbackSize > AttachmentData.MAX_ATTACHMENT_SIZE) {
                    log.warn("[M00NPlaywright] Video skipped: \"{}\" ({} MB) exceeds {} MB limit",
                        extractVideoFilename(videoPath), String.format("%.1f", fallbackSize / (1024.0 * 1024.0)),
                        AttachmentData.MAX_ATTACHMENT_SIZE / (1024 * 1024));
                    return false;
                }
                byte[] videoBytes = Files.readAllBytes(videoPath);
                String filename = extractVideoFilename(videoPath);
                AttachmentData attachment = AttachmentData.video(filename, videoBytes);
                if (attachment == null) return false;
                M00NReporter.getInstance().attachToTest(testId, attachment);
                log.warn("[M00NPlaywright] ✓ Video captured after timeout ({} KB)", videoBytes.length / 1024);
                return true;
            }
            
            log.warn("[M00NPlaywright] Video file not ready after {}ms timeout", VIDEO_FINALIZE_TIMEOUT_MS);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[M00NPlaywright] Video capture interrupted");
            return false;
        } catch (Exception e) {
            log.error("[M00NPlaywright] ✗ Video failed: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Extracts video filename from path, with fallback to default.
     * 
     * @param videoPath The path to the video file
     * @return The filename (e.g., "test-video.webm") or default "video.webm"
     */
    private static String extractVideoFilename(Path videoPath) {
        if (videoPath == null || videoPath.getFileName() == null) {
            return "video.webm";
        }
        String filename = videoPath.getFileName().toString();
        return filename.isEmpty() ? "video.webm" : filename;
    }
    
    /**
     * Close context quietly (used internally).
     */
    static void closeContextQuietly() {
        Object context = CURRENT_CONTEXT.get();
        if (context == null) return;
        
        try {
            // Use interface class to avoid Java module access restrictions
            var browserContextClass = Class.forName("com.microsoft.playwright.BrowserContext");
            var closeMethod = browserContextClass.getMethod("close");
            closeMethod.invoke(context);
        } catch (Exception e) {
            log.debug("[M00NPlaywright] Failed to close context: {}", e.getMessage());
        }
    }
}
