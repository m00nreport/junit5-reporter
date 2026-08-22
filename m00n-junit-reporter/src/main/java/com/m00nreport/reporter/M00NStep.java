package com.m00nreport.reporter;

import com.m00nreport.reporter.model.TestResult;

import java.util.Optional;

/**
 * Thread-local storage for the current test result and step nesting.
 * 
 * <p>Used internally by the reporter to track which test is currently executing
 * and the current nesting level for nested steps.</p>
 * 
 * <p>The {@link StepProxy} and {@link M00NStepAspect} use this to attach 
 * steps to the correct test with proper nesting levels.</p>
 * 
 * <h2>Nested Steps:</h2>
 * <p>When a @Step method calls another @Step method, the inner step
 * automatically gets a higher nesting level. This creates a parent-child
 * relationship in the M00N Report UI.</p>
 * 
 * <pre>{@code
 * @Step("Perform login")
 * void login() {           // nestingLevel = 0
 *     enterUsername();     // nestingLevel = 1
 *     enterPassword();     // nestingLevel = 1
 *     clickSubmit();       // nestingLevel = 1
 * }
 * }</pre>
 */
public final class M00NStep {
    
    private static final ThreadLocal<TestResult> CURRENT_TEST = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> TEST_FAILED = new ThreadLocal<>();
    private static final ThreadLocal<Integer> NESTING_LEVEL = ThreadLocal.withInitial(() -> 0);
    
    private M00NStep() {} // Utility class
    
    /**
     * Sets the current test result for the current thread.
     * Called automatically by M00NExtension.
     */
    public static void setCurrentTest(TestResult result) {
        CURRENT_TEST.set(result);
        TEST_FAILED.set(false); // Reset failure flag for new test
        NESTING_LEVEL.set(0);   // Reset nesting level for new test
    }
    
    /**
     * Clears the current test result.
     */
    public static void clearCurrentTest() {
        CURRENT_TEST.remove();
        TEST_FAILED.remove();
        NESTING_LEVEL.remove();
    }
    
    /**
     * Gets the current test result, or null if not in a test context.
     */
    public static TestResult getCurrentTest() {
        return CURRENT_TEST.get();
    }
    
    /**
     * Gets the current test result as an Optional.
     */
    public static Optional<TestResult> current() {
        return Optional.ofNullable(CURRENT_TEST.get());
    }
    
    /**
     * Mark the current test as failed.
     * Triggers screenshot capture in @AfterEach.
     */
    public static void markFailed() {
        TEST_FAILED.set(true);
    }
    
    /**
     * Check if current test failed.
     */
    public static boolean isCurrentTestFailed() {
        return Boolean.TRUE.equals(TEST_FAILED.get());
    }
    
    // =========================================================================
    // Nested Step Tracking
    // =========================================================================
    
    /**
     * Gets the current nesting level for steps.
     * Level 0 = top-level step, Level 1 = nested inside another step, etc.
     * 
     * @return current nesting level (0 or higher)
     */
    public static int getCurrentNestingLevel() {
        return NESTING_LEVEL.get();
    }
    
    /**
     * Increments the nesting level when entering a nested step.
     * Call this before creating a step that's inside another step.
     */
    public static void incrementNestingLevel() {
        NESTING_LEVEL.set(NESTING_LEVEL.get() + 1);
    }
    
    /**
     * Decrements the nesting level when exiting a nested step.
     * Call this after a nested step completes.
     */
    public static void decrementNestingLevel() {
        int current = NESTING_LEVEL.get();
        if (current > 0) {
            NESTING_LEVEL.set(current - 1);
        }
    }
}
