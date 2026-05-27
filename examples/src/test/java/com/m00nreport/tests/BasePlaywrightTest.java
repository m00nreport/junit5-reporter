package com.m00nreport.tests;

import com.m00nreport.reporter.M00NReporter;
import com.m00nreport.reporter.M00NStep;
import com.m00nreport.reporter.model.AttachmentData;
import com.m00nreport.reporter.model.TestResult;
import com.microsoft.playwright.*;
import com.microsoft.playwright.junit.Options;
import com.microsoft.playwright.junit.OptionsFactory;
import com.microsoft.playwright.junit.UsePlaywright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Base class for Playwright tests using the official JUnit extension.
 * 
 * <p>Uses {@link UsePlaywright} with custom options for:</p>
 * <ul>
 *   <li>Video recording (saved on context close)</li>
 *   <li>Tracing (started/stopped in lifecycle methods)</li>
 * </ul>
 * 
 * @see <a href="https://playwright.dev/java/docs/videos#record-video">Playwright Videos</a>
 * @see <a href="https://playwright.dev/java/docs/trace-viewer-intro">Playwright Trace Viewer</a>
 */
@UsePlaywright(BasePlaywrightTest.CustomOptions.class)
public abstract class BasePlaywrightTest {
    
    private static final Logger log = LoggerFactory.getLogger(BasePlaywrightTest.class);
    
    // Directory for test artifacts
    protected static final Path ARTIFACTS_DIR = Paths.get("test-results");
    
    // Store context and page for trace/video capture
    protected BrowserContext browserContext;
    protected Page page;
    private String currentTestId;
    private String currentTestName;
    
    /**
     * Custom Playwright options with video recording enabled.
     */
    public static class CustomOptions implements OptionsFactory {
        @Override
        public Options getOptions() {
            return new Options()
                .setHeadless(true)
                .setContextOptions(new Browser.NewContextOptions()
                    .setViewportSize(1280, 720)
                    .setRecordVideoDir(ARTIFACTS_DIR.resolve("videos"))
                    .setRecordVideoSize(1280, 720));
        }
    }
    
    /**
     * Called before each test to start tracing.
     */
    @BeforeEach
    void setupTracing(BrowserContext context, Page page, TestInfo testInfo) {
        this.browserContext = context;
        this.page = page;
        this.currentTestName = sanitizeFilename(testInfo.getDisplayName());
        
        // Capture test ID from M00N reporter for later attachment
        this.currentTestId = M00NStep.current()
            .map(TestResult::getTestId)
            .orElse(null);
        
        try {
            Files.createDirectories(ARTIFACTS_DIR.resolve("traces"));
        } catch (Exception e) {
            log.debug("Failed to create traces dir: {}", e.getMessage());
        }
        
        // Start tracing
        try {
            context.tracing().start(new Tracing.StartOptions()
                .setScreenshots(true)
                .setSnapshots(true)
                .setSources(true));
            log.debug("[BasePlaywrightTest] Tracing started for: {}", currentTestName);
        } catch (Exception e) {
            log.warn("[BasePlaywrightTest] Failed to start tracing: {}", e.getMessage());
        }
    }
    
    /**
     * Called after each test to stop tracing and capture artifacts.
     * Only attaches screenshot, trace, and video for FAILED tests.
     */
    @AfterEach
    void captureArtifacts() {
        if (browserContext == null) return;
        
        // Get test ID and failure status before they're cleared
        final String testId = this.currentTestId;
        final boolean testFailed = M00NStep.isCurrentTestFailed();
        
        // Only capture and attach artifacts for failed tests
        if (testFailed && testId != null) {
            // 1. Capture screenshot BEFORE stopping trace
            if (page != null) {
                try {
                    byte[] screenshot = page.screenshot(new Page.ScreenshotOptions()
                        .setFullPage(false));
                    M00NReporter.getInstance().attachToTest(testId,
                        AttachmentData.screenshot("screenshot.png", screenshot));
                    log.info("[BasePlaywrightTest] Screenshot attached to test {} ({} KB)", 
                        testId, screenshot.length / 1024);
                } catch (Exception e) {
                    log.debug("[BasePlaywrightTest] Failed to capture screenshot: {}", e.getMessage());
                }
            }
            
            // 2. Stop tracing and attach trace
            Path tracePath = ARTIFACTS_DIR.resolve("traces").resolve(currentTestName + "-trace.zip");
            try {
                browserContext.tracing().stop(new Tracing.StopOptions()
                    .setPath(tracePath));
                log.info("[BasePlaywrightTest] Trace saved: {}", tracePath);
                
                if (Files.exists(tracePath)) {
                    byte[] traceBytes = Files.readAllBytes(tracePath);
                    M00NReporter.getInstance().attachToTest(testId, 
                        AttachmentData.trace("trace.zip", traceBytes));
                    log.info("[BasePlaywrightTest] Trace attached to test {}", testId);
                }
            } catch (Exception e) {
                log.debug("[BasePlaywrightTest] Failed to capture trace: {}", e.getMessage());
            }
            
            // 3. Capture video path and attach async
            if (page != null && page.video() != null) {
                try {
                    Path videoPath = page.video().path();
                    if (videoPath != null) {
                        log.info("[BasePlaywrightTest] Video at: {}", videoPath);
                        
                        final Path finalVideoPath = videoPath;
                        final String finalTestId = testId;
                        
                        Thread.ofVirtual().start(() -> {
                            try {
                                Thread.sleep(1000); // Wait for context to close
                                if (Files.exists(finalVideoPath)) {
                                    byte[] videoBytes = Files.readAllBytes(finalVideoPath);
                                    M00NReporter.getInstance().attachToTest(finalTestId,
                                        AttachmentData.video("video.webm", videoBytes));
                                    log.info("[BasePlaywrightTest] Video attached to test {} ({} KB)", 
                                        finalTestId, videoBytes.length / 1024);
                                }
                            } catch (Exception e) {
                                log.debug("Failed to attach video: {}", e.getMessage());
                            }
                        });
                    }
                } catch (Exception e) {
                    log.debug("[BasePlaywrightTest] Failed to get video path: {}", e.getMessage());
                }
            }
        } else {
            // For passed tests, just stop tracing without attaching
            try {
                Path tracePath = ARTIFACTS_DIR.resolve("traces").resolve(currentTestName + "-trace.zip");
                browserContext.tracing().stop(new Tracing.StopOptions()
                    .setPath(tracePath));
                log.debug("[BasePlaywrightTest] Trace saved (not attached - test passed): {}", tracePath);
            } catch (Exception e) {
                log.debug("[BasePlaywrightTest] Failed to stop tracing: {}", e.getMessage());
            }
        }
    }
    
    private String sanitizeFilename(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9-_]", "_")
                               .replaceAll("_+", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), 100));
    }
}
