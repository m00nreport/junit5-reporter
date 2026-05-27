package com.m00nreport.tests;

import com.m00nreport.reporter.M00NStep;
import com.m00nreport.reporter.model.AttachmentData;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junitpioneer.jupiter.RetryingTest;

import java.util.concurrent.atomic.AtomicInteger;

import com.microsoft.playwright.assertions.LocatorAssertions;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class demonstrating all possible test states for M00N Report.
 * 
 * States covered:
 * - PASSED: Test completes successfully
 * - FAILED: Test fails with assertion error
 * - SKIPPED: Test is skipped via @Disabled or assumption
 * - FLAKY: Test fails on first attempt but passes on retry
 * 
 * Uses @UsePlaywright for automatic video and trace capture.
 */
@DisplayName("All Test States Demo")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AllTestStatesTests extends BasePlaywrightTest {
    
    // Counter for flaky test simulation
    private static final AtomicInteger flakyCounter = new AtomicInteger(0);
    private static final AtomicInteger retryCounter = new AtomicInteger(0);
    
    // =========================================================================
    // PASSED Tests
    // =========================================================================
    
    @Test
    @Order(1)
    @DisplayName("✅ Simple passed test")
    void simplePassedTest(Page page) {
        page.navigate("https://playwright.dev/");
        assertThat(page).hasTitle(java.util.regex.Pattern.compile(".*Playwright.*"));
    }
    
    @Test
    @Order(2)
    @DisplayName("✅ Passed test with steps")
    void passedTestWithSteps(Page page) {
        M00NStep.current().ifPresent(test -> {
            var step1 = test.addStep("Navigate to homepage", "navigation");
            page.navigate("https://playwright.dev/");
            step1.setStatus("passed");
            step1.setDuration(500L);
            
            var step2 = test.addStep("Verify title", "assertion");
            assertThat(page).hasTitle(java.util.regex.Pattern.compile(".*Playwright.*"));
            step2.setStatus("passed");
            step2.setDuration(100L);
            
            var step3 = test.addStep("Verify hero section", "assertion");
            assertThat(page.locator(".hero__title")).isVisible();
            step3.setStatus("passed");
            step3.setDuration(200L);
        });
    }
    
    @Test
    @Order(3)
    @DisplayName("✅ Passed test with screenshot attachment")
    void passedTestWithScreenshot(Page page) {
        page.navigate("https://playwright.dev/");
        
        // Take screenshot and attach
        var screenshot = page.screenshot();
        M00NStep.current().ifPresent(test ->
            test.addAttachment(AttachmentData.screenshot("homepage-screenshot", screenshot))
        );
        
        assertThat(page.locator(".hero__title")).isVisible();
    }
    
    // =========================================================================
    // FAILED Tests
    // =========================================================================
    
    @Test
    @Order(10)
    @DisplayName("❌ Failed assertion test")
    void failedAssertionTest(Page page) {
        page.navigate("https://playwright.dev/");
        
        // This will fail - intentionally wrong selector
        assertThat(page.locator("#non-existent-element-xyz"))
            .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(2000));
    }
    
    @Test
    @Order(11)
    @DisplayName("❌ Failed test with error message")
    void failedTestWithError(Page page) {
        page.navigate("https://playwright.dev/");
        
        // Capture screenshot before failure
        var screenshot = page.screenshot();
        M00NStep.current().ifPresent(test ->
            test.addAttachment(AttachmentData.screenshot("before-failure", screenshot))
        );
        
        // This will fail with custom message
        fail("This test intentionally fails to demonstrate error reporting");
    }
    
    @Test
    @Order(12)
    @DisplayName("❌ Failed test with exception")
    void failedTestWithException(Page page) {
        page.navigate("https://playwright.dev/");
        
        // This will throw an exception
        throw new RuntimeException("Simulated runtime exception for testing");
    }
    
    // =========================================================================
    // SKIPPED Tests
    // =========================================================================
    
    @Test
    @Order(20)
    @Disabled("This test is disabled for demonstration")
    @DisplayName("⏭️ Disabled test")
    void disabledTest(Page page) {
        fail("This should never execute");
    }
    
    @Test
    @Order(21)
    @DisplayName("⏭️ Skipped via assumption")
    void skippedViaAssumption(Page page) {
        // Skip based on assumption
        Assumptions.assumeTrue(false, "Skipping test via assumption for demonstration");
        fail("This should never execute");
    }
    
    @Test
    @Order(22)
    @EnabledIf("isFeatureEnabled")
    @DisplayName("⏭️ Conditionally skipped test")
    void conditionallySkippedTest(Page page) {
        fail("This should never execute - condition is false");
    }
    
    static boolean isFeatureEnabled() {
        return false; // Always disabled for demo
    }
    
    // =========================================================================
    // FLAKY Tests (with retries via @RetryingTest)
    // =========================================================================
    
    @RetryingTest(maxAttempts = 3, name = "🔄 Flaky test - Attempt {index}")
    @Order(30)
    void flakyTest(Page page) {
        int attempt = flakyCounter.incrementAndGet();
        
        page.navigate("https://playwright.dev/");
        
        M00NStep.current().ifPresent(test -> {
            var step = test.addStep("Attempt #" + attempt, "info");
            step.setStatus("passed");
        });
        
        // Fail on first attempt, pass on subsequent attempts
        if (attempt == 1) {
            fail("First attempt fails (simulating flaky behavior)");
        }
        
        assertThat(page.locator(".hero__title")).isVisible();
    }
    
    @RetryingTest(maxAttempts = 3, name = "🔄 Retry test always fails - Attempt {index}")
    @Order(31)
    void alwaysFailsTest(Page page) {
        int attempt = retryCounter.incrementAndGet();
        
        page.navigate("https://playwright.dev/");
        
        M00NStep.current().ifPresent(test -> {
            var step = test.addStep("Retry attempt #" + attempt, "info");
            step.setStatus("passed");
        });
        
        // Always fail - will exhaust retries
        fail("This test always fails (attempt " + attempt + ")");
    }
    
    // Counter for retry-then-pass test
    private static final AtomicInteger retryPassCounter = new AtomicInteger(0);
    
    @RetryingTest(maxAttempts = 2, name = "🔄 First fails, retry passes - Attempt {index}")
    @Order(32)
    void firstFailsRetryPasses(Page page) {
        int attempt = retryPassCounter.incrementAndGet();
        
        page.navigate("https://playwright.dev/");
        
        M00NStep.current().ifPresent(test -> {
            var step = test.addStep("Execution attempt #" + attempt, "info");
            step.setStatus("passed");
            step.setDuration(100L);
        });
        
        // Fail on first attempt, pass on second
        if (attempt == 1) {
            fail("First attempt intentionally fails - retry should pass");
        }
        
        // Second attempt passes
        assertThat(page.locator(".hero__title")).isVisible();
    }
    
    // =========================================================================
    // Tests with Multiple Steps
    // =========================================================================
    
    @Test
    @Order(40)
    @DisplayName("📋 Test with multiple nested steps")
    void testWithNestedSteps(Page page) {
        M00NStep.current().ifPresent(test -> {
            // Step 1: Setup
            var setupStep = test.addStep("Setup", "hook");
            setupStep.setStatus("passed");
            setupStep.setDuration(50L);
            
            // Step 2: Navigation
            var navStep = test.addStep("Navigate to Playwright docs", "navigation");
            page.navigate("https://playwright.dev/docs/intro");
            navStep.setStatus("passed");
            navStep.setDuration(1500L);
            
            // Step 3: Verify sidebar
            var sidebarStep = test.addStep("Verify sidebar is visible", "assertion");
            assertThat(page.locator(".theme-doc-sidebar-menu")).isVisible();
            sidebarStep.setStatus("passed");
            sidebarStep.setDuration(200L);
            
            // Step 4: Verify content
            var contentStep = test.addStep("Verify article content", "assertion");
            assertThat(page.locator("article")).isVisible();
            contentStep.setStatus("passed");
            contentStep.setDuration(100L);
            
            // Step 5: Take screenshot
            var screenshotStep = test.addStep("Capture screenshot", "attachment");
            var screenshot = page.screenshot();
            test.addAttachment(AttachmentData.screenshot("docs-page", screenshot));
            screenshotStep.setStatus("passed");
            screenshotStep.setDuration(300L);
        });
    }
    
    @Test
    @Order(41)
    @DisplayName("📋 Test with failing step")
    void testWithFailingStep(Page page) {
        M00NStep.current().ifPresent(test -> {
            // Step 1: Passes
            var step1 = test.addStep("Navigate to homepage", "navigation");
            page.navigate("https://playwright.dev/");
            step1.setStatus("passed");
            step1.setDuration(1000L);
            
            // Step 2: Passes
            var step2 = test.addStep("Verify page loaded", "assertion");
            assertThat(page.locator(".hero__title")).isVisible();
            step2.setStatus("passed");
            step2.setDuration(200L);
            
            // Step 3: Fails
            var step3 = test.addStep("Click non-existent button", "action");
            try {
                page.locator("#fake-button").click(new Locator.ClickOptions().setTimeout(1000));
                step3.setStatus("passed");
            } catch (Exception e) {
                step3.setStatus("failed");
                step3.setDuration(1000L);
                throw e;
            }
        });
    }
}
