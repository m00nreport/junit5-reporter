package com.m00nreport.reporter;

import com.m00nreport.reporter.annotations.TestCaseId;
import com.m00nreport.reporter.model.TestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JUnit 5 Extension for M00N Report integration.
 * 
 * <p>This extension is automatically registered via ServiceLoader.
 * No annotation needed on test classes.</p>
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>Automatic test lifecycle tracking</li>
 *   <li>Retry detection (for @RetryingTest)</li>
 *   <li>Thread-safe parallel execution</li>
 *   <li>Catches intermediate retry failures via InvocationInterceptor</li>
 * </ul>
 */
public class M00NExtension implements 
        BeforeEachCallback,
        AfterEachCallback,
        TestWatcher,
        InvocationInterceptor {
    
    private static final Logger log = LoggerFactory.getLogger(M00NExtension.class);
    
    private static final ExtensionContext.Namespace NAMESPACE = 
        ExtensionContext.Namespace.create(M00NExtension.class);
    private static final String TEST_RESULT_KEY = "testResult";
    
    // Thread-local to track if test was already reported (avoids store access after close)
    // This is needed because JUnit closes the store between retry attempts in @RetryingTest
    private static final ThreadLocal<Boolean> reportedInCurrentTest = ThreadLocal.withInitial(() -> false);
    
    // Pattern to detect retry attempt from display name
    // ONLY matches JUnit Pioneer's @RetryingTest patterns:
    // - "Attempt 1", "Attempt 2" (explicit attempt indicator)
    // - "Test name - Attempt 3" (test name with attempt suffix)
    // NOTE: Does NOT match "[1]", "[2]" or "#1" - these are parameterized test invocations, NOT retries!
    private static final Pattern RETRY_PATTERN = Pattern.compile(
        "\\bAttempt\\s+(\\d+)",
        Pattern.CASE_INSENSITIVE
    );

    // Extracts the 1-based invocation index from a test template unique ID,
    // e.g. ".../[test-template-invocation:#3]" -> 3
    private static final Pattern INVOCATION_INDEX_PATTERN = Pattern.compile(
        "test-template-invocation:#(\\d+)"
    );
    
    // =========================================================================
    // Test Lifecycle
    // =========================================================================
    
    @Override
    public void beforeEach(ExtensionContext context) {
        try {
            M00NReporter reporter = M00NReporter.getInstance();
            if (!reporter.isEnabled()) return;
            
            // Extract display names for titlePath
            String suiteName = getSuiteDisplayName(context);
            
            // Get test name: prefer @DisplayName on method, fallback to method name.
            // Appends [TC-N] markers from @TestCaseId so the ingest service auto-links cases.
            String testName = getTestDisplayNameWithCaseIds(context);
            
            // Get retry attempt from context (handles @RetryingTest)
            // attempt is 1-indexed, retry is 0-indexed
            int attempt = getRetryAttemptFromContext(context);
            int retry = attempt > 0 ? attempt - 1 : 0;
            
            // Debug: log what we're detecting
            if (log.isDebugEnabled()) {
                log.debug("[M00NExtension] beforeEach - displayName: '{}', uniqueId: '{}', attempt: {}, retry: {}", 
                    context.getDisplayName(), 
                    context.getUniqueId().substring(Math.max(0, context.getUniqueId().length() - 50)), 
                    attempt, retry);
            }
            
            // Keep actual class name for filePath
            String className = context.getRequiredTestClass().getName();
            
            // Pass retry count to startTest so it's included in the API request
            TestResult result = reporter.startTest(suiteName, testName, className, retry);
            if (result != null) {
                log.debug("[M00NExtension] Test started: suite='{}', test='{}', retry={}", 
                    suiteName, testName, retry);
                
                // Store in extension context
                context.getStore(NAMESPACE).put(TEST_RESULT_KEY, result);
                // Clear the reported flag for this new test/retry attempt
                // Use thread-local to avoid "store closed" errors with @RetryingTest
                reportedInCurrentTest.set(false);
                // Set thread-local for StepProxy
                M00NStep.setCurrentTest(result);
            }
        } catch (Exception e) {
            log.warn("[M00NExtension] beforeEach failed: {}", e.getMessage());
        }
    }
    
    @Override
    public void afterEach(ExtensionContext context) {
        // Clean up thread-locals EXCEPT reportedInCurrentTest
        // (TestWatcher callbacks run AFTER afterEach and need this flag)
        M00NStep.clearCurrentTest();
        M00NPlaywright.clear();  // Clean up Playwright registrations
        // NOTE: reportedInCurrentTest is cleared in beforeEach, not here
    }
    
    /**
     * Intercept test method execution to catch results BEFORE JUnit Pioneer's retry logic.
     * This ensures each retry attempt is properly reported, not just the final result.
     */
    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        interceptTest(invocation, extensionContext);
    }
    
    /**
     * Intercept test template method execution (for @RetryingTest, @ParameterizedTest, etc.).
     * This ensures each retry attempt is properly reported.
     */
    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        interceptTest(invocation, extensionContext);
    }
    
    /**
     * Common test interception logic for both regular tests and test templates.
     * 
     * IMPORTANT: Captures Playwright artifacts IMMEDIATELY on failure,
     * BEFORE re-throwing the exception. This ensures capture happens before
     * any @AfterEach or other extensions that might close the browser context.
     */
    private void interceptTest(Invocation<Void> invocation, ExtensionContext extensionContext) throws Throwable {
        TestResult testResult = getTestResultSafely(extensionContext);
        
        if (testResult == null) {
            // Reporter not tracking this test, just proceed
            invocation.proceed();
            return;
        }
        
        try {
            invocation.proceed();
            // Test passed - report immediately
            reportTestResult(extensionContext, "passed", null);
        } catch (Throwable t) {
            // Mark test as failed
            M00NStep.markFailed();
            
            // CAPTURE PLAYWRIGHT ARTIFACTS IMMEDIATELY - before any @AfterEach runs!
            // This is critical when customers have their own extensions that close the context
            capturePlaywrightArtifacts(testResult, extensionContext);
            
            // Test failed - report immediately (before JUnit Pioneer catches for retry)
            reportTestResult(extensionContext, "failed", t);
            throw t; // Re-throw so JUnit Pioneer can handle retry
        }
    }
    
    /**
     * Capture Playwright artifacts (screenshot, trace) immediately on failure.
     * 
     * First checks if test implements PlaywrightTestProvider for auto-registration,
     * then falls back to manual M00NPlaywright.setPage() registration.
     * 
     * This runs BEFORE @AfterEach, so the browser is still open.
     */
    private void capturePlaywrightArtifacts(TestResult testResult, ExtensionContext context) {
        if (testResult == null) return;
        
        String testId = testResult.getTestId();
        String testName = sanitizeFilename(context.getDisplayName());
        
        // Auto-register from PlaywrightTestProvider if test implements it
        // This allows customers to implement the interface instead of calling M00NPlaywright.setPage()
        autoRegisterFromProvider(context);
        
        // Capture screenshot first (before any state changes)
        if (M00NPlaywright.hasPage()) {
            log.debug("[M00NExtension] Capturing Playwright artifacts for: {}", testName);
            M00NPlaywright.captureScreenshot(testId);
            
            // Capture trace if context was registered
            if (M00NPlaywright.hasContext()) {
                M00NPlaywright.captureTrace(testId, testName);
            }
        }
    }
    
    /**
     * Auto-register Playwright objects for artifact capture.
     * 
     * Priority order:
     * 1. Manual registration via M00NPlaywright.setPage() - highest priority
     * 2. PlaywrightTestProvider interface - explicit contract
     * 3. Auto-detection via reflection - zero-config fallback
     * 
     * This enables ZERO code changes for customers - just add dependency + config!
     */
    private void autoRegisterFromProvider(ExtensionContext context) {
        // Already registered manually? Skip auto-detection
        if (M00NPlaywright.hasPage()) {
            return;
        }
        
        try {
            Object testInstance = context.getRequiredTestInstance();
            
            // Priority 1: Check if test implements PlaywrightTestProvider
            if (testInstance instanceof PlaywrightTestProvider provider) {
                Object page = provider.getPage();
                if (page != null) {
                    M00NPlaywright.setPage(page);
                    log.debug("[M00NExtension] Auto-registered Page from PlaywrightTestProvider");
                }
                
                if (!M00NPlaywright.hasContext()) {
                    Object browserContext = provider.getContext();
                    if (browserContext != null) {
                        M00NPlaywright.setContext(browserContext);
                        log.debug("[M00NExtension] Auto-registered Context from PlaywrightTestProvider");
                    }
                }
                return;
            }
            
            // Priority 2: Auto-detect Playwright fields via reflection (ZERO-CONFIG!)
            autoDetectPlaywrightFields(testInstance);
            
        } catch (Exception e) {
            log.debug("[M00NExtension] Could not auto-register Playwright: {}", e.getMessage());
        }
    }
    
    /**
     * Auto-detect Playwright Page and BrowserContext fields via reflection.
     * This enables ZERO code changes for customers migrating from other reporters.
     * 
     * Scans test instance (and parent classes) for fields of type:
     * - com.microsoft.playwright.Page
     * - com.microsoft.playwright.BrowserContext
     */
    private void autoDetectPlaywrightFields(Object testInstance) {
        Class<?> clazz = testInstance.getClass();
        
        // Scan class hierarchy (including parent classes)
        while (clazz != null && clazz != Object.class) {
            for (var field : clazz.getDeclaredFields()) {
                try {
                    // Handle Java 17+ module restrictions gracefully
                    try {
                        field.setAccessible(true);
                    } catch (RuntimeException e) {
                        // InaccessibleObjectException (Java 9+) or SecurityException
                        log.debug("[M00NExtension] Cannot access field {} (module restrictions): {}", 
                            field.getName(), e.getMessage());
                        continue;
                    }
                    
                    Object value = field.get(testInstance);
                    
                    if (value == null) continue;
                    
                    String typeName = value.getClass().getName();
                    
                    // Detect Playwright Page (including impl class)
                    if (!M00NPlaywright.hasPage() && isPlaywrightPage(value, typeName)) {
                        M00NPlaywright.setPage(value);
                        log.debug("[M00NExtension] Auto-detected Page field: {}.{}", 
                            clazz.getSimpleName(), field.getName());
                    }
                    
                    // Detect Playwright BrowserContext
                    if (!M00NPlaywright.hasContext() && isPlaywrightContext(value, typeName)) {
                        M00NPlaywright.setContext(value);
                        log.debug("[M00NExtension] Auto-detected BrowserContext field: {}.{}", 
                            clazz.getSimpleName(), field.getName());
                    }
                    
                } catch (Exception e) {
                    // Skip inaccessible fields
                    log.debug("[M00NExtension] Error accessing field {}: {}", field.getName(), e.getMessage());
                }
            }
            clazz = clazz.getSuperclass();
        }
    }
    
    /**
     * Check if object is a Playwright Page (handles impl classes and wrappers).
     */
    private boolean isPlaywrightPage(Object obj, String typeName) {
        // Direct Page implementation
        if (typeName.contains("playwright") && typeName.contains("Page")) {
            return true;
        }
        
        // Check interfaces
        for (Class<?> iface : obj.getClass().getInterfaces()) {
            if (iface.getName().equals("com.microsoft.playwright.Page")) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if object is a Playwright BrowserContext.
     */
    private boolean isPlaywrightContext(Object obj, String typeName) {
        // Direct BrowserContext implementation
        if (typeName.contains("playwright") && typeName.contains("BrowserContext")) {
            return true;
        }
        
        // Check interfaces
        for (Class<?> iface : obj.getClass().getInterfaces()) {
            if (iface.getName().equals("com.microsoft.playwright.BrowserContext")) {
                return true;
            }
        }
        
        return false;
    }
    
    private String sanitizeFilename(String name) {
        if (name == null) return "unknown";
        String sanitized = name.replaceAll("[^a-zA-Z0-9-_]", "_")
                               .replaceAll("_+", "_");
        return sanitized.substring(0, Math.min(sanitized.length(), 100));
    }
    
    /**
     * Report test result immediately. Used by interceptTestMethod to ensure
     * each retry attempt is reported, not just the final result.
     */
    private void reportTestResult(ExtensionContext context, String status, Throwable error) {
        try {
            // Use thread-local to check if already reported (avoids store access after close)
            if (reportedInCurrentTest.get()) {
                return; // Already reported by interceptor
            }
            
            TestResult testResult = getTestResultSafely(context);
            if (testResult == null) return;
            
            // Mark as reported using thread-local (store may be closed for @RetryingTest)
            reportedInCurrentTest.set(true);
            
            M00NReporter reporter = M00NReporter.getInstance();
            if (!reporter.isEnabled()) return;
            
            String suiteName = getSuiteDisplayName(context);
            String testName = getTestDisplayNameWithCaseIds(context);

            log.debug("[M00NExtension] Reporting test via interceptor: suite='{}', test='{}', status='{}', retry={}",
                suiteName, testName, status, testResult.getRetry());
            
            reporter.completeTest(suiteName, testName, status, error);
        } catch (Exception e) {
            log.warn("[M00NExtension] reportTestResult failed: {}", e.getMessage());
        }
    }
    
    // =========================================================================
    // TestWatcher - Final test status
    // =========================================================================
    
    @Override
    public void testSuccessful(ExtensionContext context) {
        completeTest(context, "passed", null);
    }
    
    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        completeTest(context, "failed", cause);
    }
    
    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        // JUnit Pioneer's @RetryingTest calls testAborted when a retry attempt fails
        // but wraps the failure in TestAbortedException. We need to detect this and
        // report as "failed" not "skipped".
        //
        // Real skips (Assumptions.assumeTrue) have TestAbortedException without a cause.
        // Retry failures have TestAbortedException WITH a wrapped cause or message.
        String status = "skipped";
        
        if (cause != null) {
            if (cause instanceof org.opentest4j.TestAbortedException) {
                // Check if it's a wrapped failure (has cause) or has a failure-like message
                if (cause.getCause() != null || 
                    (cause.getMessage() != null && !cause.getMessage().contains("Assumption"))) {
                    status = "failed";
                }
            } else {
                // Any other exception is a real failure
                status = "failed";
            }
        }
        
        completeTest(context, status, cause);
    }
    
    @Override
    public void testDisabled(ExtensionContext context, Optional<String> reason) {
        try {
            M00NReporter reporter = M00NReporter.getInstance();
            if (!reporter.isEnabled()) return;
            
            String suiteName = getSuiteDisplayName(context);
            String testName = getTestDisplayNameWithCaseIds(context);
            String className = context.getRequiredTestClass().getName();

            // Disabled tests are always first run (retry=0)
            TestResult result = reporter.startTest(suiteName, testName, className, 0);
            if (result != null) {
                reporter.completeTest(suiteName, testName, "skipped", null);
            }
        } catch (Exception e) {
            log.warn("[M00NExtension] testDisabled failed: {}", e.getMessage());
        }
    }
    
    private void completeTest(ExtensionContext context, String status, Throwable error) {
        try {
            // Check if already reported by interceptor using thread-local
            // (avoids store access after close for @RetryingTest scenarios)
            if (reportedInCurrentTest.get()) {
                log.debug("[M00NExtension] Test already reported by interceptor, skipping TestWatcher callback");
                return;
            }
            
            M00NReporter reporter = M00NReporter.getInstance();
            if (!reporter.isEnabled()) return;
            
            String suiteName = getSuiteDisplayName(context);
            String testName = getTestDisplayNameWithCaseIds(context);

            // Mark as reported before calling completeTest
            reportedInCurrentTest.set(true);
            
            reporter.completeTest(suiteName, testName, status, error);
        } catch (Exception e) {
            log.warn("[M00NExtension] completeTest failed: {}", e.getMessage());
        }
    }
    
    /**
     * Safely get TestResult from context store, handling closed store gracefully.
     * JUnit 5 closes the store between retry attempts in @RetryingTest, which can
     * cause "NamespacedHierarchicalStore cannot be modified after closed" errors.
     */
    private TestResult getTestResultSafely(ExtensionContext context) {
        try {
            return context.getStore(NAMESPACE).get(TEST_RESULT_KEY, TestResult.class);
        } catch (Exception e) {
            // Store is closed (e.g., between retry attempts)
            log.debug("[M00NExtension] Store already closed, using thread-local fallback");
            return M00NStep.current().orElse(null);
        }
    }
    
    // =========================================================================
    // Helpers
    // =========================================================================
    
    /**
     * Gets the suite display name from @DisplayName on class or simple class name.
     */
    private String getSuiteDisplayName(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();
        
        DisplayName annotation = testClass.getAnnotation(DisplayName.class);
        if (annotation != null && !annotation.value().isEmpty()) {
            return annotation.value();
        }
        
        return testClass.getSimpleName();
    }
    
    /**
     * Gets the test display name from @DisplayName on method or context display name.
     * For @RetryingTest, extracts the base name by stripping the attempt suffix.
     * For @ParameterizedTest, uses the full context display name (includes parameters).
     */
    private String getTestDisplayName(ExtensionContext context) {
        try {
            var method = context.getRequiredTestMethod();
            String uniqueId = context.getUniqueId();
            boolean isTestTemplate = uniqueId.contains("test-template-invocation");
            
            // Check for @DisplayName on method
            // BUT: For test templates (parameterized/repeated), we MUST use context display name
            // to get the unique invocation name including parameters/index
            DisplayName displayName = method.getAnnotation(DisplayName.class);
            if (displayName != null && !displayName.value().isEmpty() && !isTestTemplate) {
                return displayName.value();
            }
            
            // Use context.getDisplayName() for all other cases
            // This correctly handles:
            // - @ParameterizedTest: "1: Test name with params [value1] [value2]"
            // - @RepeatedTest: "repetition 1 of 5"
            // - Regular tests: method name or custom display name
            String contextDisplayName = context.getDisplayName();
            
            if (contextDisplayName != null && !contextDisplayName.isEmpty()) {
                // For @RetryingTest, strip the attempt suffix
                // e.g., "🔄 Flaky test - Attempt 1" -> "🔄 Flaky test"
                if (RETRY_PATTERN.matcher(contextDisplayName).find()) {
                    String baseName = RETRY_PATTERN.matcher(contextDisplayName)
                        .replaceAll("")
                        .replaceAll("\\s*[-–]\\s*$", "")  // Remove trailing dashes
                        .trim();
                    if (!baseName.isEmpty()) {
                        return baseName;
                    }
                }
                
                // Return context display name as-is (includes parameterized test info)
                return contextDisplayName;
            }
            
            // Ultimate fallback to method name (humanized)
            return humanizeMethodName(method.getName());
        } catch (Exception e) {
            // Fallback to context display name
            return context.getDisplayName();
        }
    }
    
    /**
     * Converts camelCase method name to readable format.
     * e.g., "testUserLogin" -> "Test user login"
     */
    private String humanizeMethodName(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return methodName;
        }
        
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < methodName.length(); i++) {
            char c = methodName.charAt(i);
            if (i == 0) {
                result.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                result.append(' ').append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
    
    /**
     * Same as {@link #getTestDisplayName(ExtensionContext)} but appends
     * {@code [TC-N]} markers for every case number resolved from
     * {@link com.m00nreport.reporter.annotations.TestCaseId}. The ingest
     * service's existing extractor picks these up and links the cases.
     *
     * <p>Idempotent: produces the same string within an invocation so the
     * reporter's startTest / completeTest lookup keys match.</p>
     */
    private String getTestDisplayNameWithCaseIds(ExtensionContext context) {
        String base = getTestDisplayName(context);
        List<Integer> caseNumbers = resolveTestCaseNumbers(context);
        if (caseNumbers.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder(base);
        for (int num : caseNumbers) {
            sb.append(" [TC-").append(num).append("]");
        }
        return sb.toString();
    }

    /**
     * Resolves {@link com.m00nreport.reporter.annotations.TestCaseId} for the
     * current invocation. Method-level annotation overrides class-level.
     *
     * <p>Mapping rules:</p>
     * <ul>
     *   <li>{@code parametrized=true} on a test template invocation:
     *       returns {@code [value[N-1]]} where N is the 1-based invocation index.</li>
     *   <li>{@code parametrized=false} or non-template test: returns all parsed values
     *       (all cases linked to this single execution).</li>
     *   <li>Misuse (parametrized=true on non-template): logs a warning and
     *       returns the first value only.</li>
     * </ul>
     */
    private List<Integer> resolveTestCaseNumbers(ExtensionContext context) {
        try {
            TestCaseId annotation = null;
            try {
                Method method = context.getRequiredTestMethod();
                annotation = method.getAnnotation(TestCaseId.class);
            } catch (Exception ignored) {
                // No method context (e.g. class-disabled) - fall through to class lookup
            }
            if (annotation == null) {
                Class<?> testClass = context.getRequiredTestClass();
                annotation = testClass.getAnnotation(TestCaseId.class);
            }
            if (annotation == null || annotation.value() == null || annotation.value().isBlank()) {
                return List.of();
            }

            List<Integer> values = new ArrayList<>();
            for (String token : annotation.value().split(",")) {
                String trimmed = token.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    values.add(Integer.parseInt(trimmed));
                } catch (NumberFormatException nfe) {
                    log.warn("[M00NExtension] @TestCaseId: skipping non-numeric value '{}'", trimmed);
                }
            }
            if (values.isEmpty()) {
                return List.of();
            }

            boolean isTemplate = context.getUniqueId().contains("test-template-invocation");

            if (annotation.parametrized()) {
                if (!isTemplate) {
                    log.warn("[M00NExtension] @TestCaseId(parametrized=true) on non-template test '{}'; using first value only",
                        context.getDisplayName());
                    return List.of(values.get(0));
                }
                int invocation = extractInvocationIndex(context.getUniqueId());
                if (invocation < 1) {
                    return List.of();
                }
                if (invocation > values.size()) {
                    log.warn("[M00NExtension] @TestCaseId(parametrized=true): invocation #{} exceeds value count {} for '{}'",
                        invocation, values.size(), context.getDisplayName());
                    return List.of();
                }
                return List.of(values.get(invocation - 1));
            }

            // Non-parametrized: link all values to every invocation
            return values;
        } catch (Exception e) {
            log.debug("[M00NExtension] @TestCaseId resolution failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Extracts the 1-based invocation index from a test template unique ID.
     * Returns -1 if no invocation segment is present.
     */
    private int extractInvocationIndex(String uniqueId) {
        if (uniqueId == null) return -1;
        Matcher matcher = INVOCATION_INDEX_PATTERN.matcher(uniqueId);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return -1;
    }

    /**
     * Parses retry information from test display name.
     * Only matches JUnit Pioneer's @RetryingTest "Attempt N" pattern.
     * Does NOT match parameterized test patterns like "[1]" or "#1".
     */
    private RetryInfo parseRetryInfo(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return new RetryInfo("", 0);  // Empty string instead of null to avoid NPE
        }
        
        Matcher matcher = RETRY_PATTERN.matcher(displayName);
        
        if (matcher.find()) {
            int attempt = Integer.parseInt(matcher.group(1));
            // Remove the attempt suffix from the name
            String cleanName = displayName.substring(0, matcher.start()).trim();
            // Remove trailing delimiters
            cleanName = cleanName.replaceAll("\\s*[:\\-–]\\s*$", "").trim();
            
            log.debug("[M00NExtension] parseRetryInfo: input='{}', matched='{}', attempt={}", 
                displayName, matcher.group(0), attempt);
            
            return new RetryInfo(cleanName.isEmpty() ? displayName : cleanName, attempt);
        }
        
        log.debug("[M00NExtension] parseRetryInfo: input='{}', no match found", displayName);
        return new RetryInfo(displayName, 0);
    }
    
    /**
     * Gets retry attempt from extension context.
     * 
     * IMPORTANT: Only JUnit Pioneer's @RetryingTest creates actual retries.
     * Other test templates (@ParameterizedTest, @RepeatedTest) use invocation numbers
     * that are NOT retries and should NOT be counted.
     * 
     * JUnit Pioneer's @RetryingTest default display name includes "Attempt N":
     * - "Attempt 1", "Attempt 2", etc.
     * - Or custom: "My Test - Attempt 3"
     * 
     * We ONLY detect retries from the explicit "Attempt N" pattern in display names.
     */
    private int getRetryAttemptFromContext(ExtensionContext context) {
        String displayName = context.getDisplayName();
        
        log.debug("[M00NExtension] getRetryAttemptFromContext - displayName='{}'", displayName);
        
        // Only parse "Attempt N" from display name - this is JUnit Pioneer's @RetryingTest pattern
        // Do NOT use unique ID patterns like [test-template-invocation:#N] - those are 
        // sequential invocation counters for ALL test templates, not retry indicators!
        RetryInfo info = parseRetryInfo(displayName);
        if (info.attempt > 0) {
            log.debug("[M00NExtension] Found retry attempt {} from displayName", info.attempt);
            return info.attempt;
        }
        
        log.debug("[M00NExtension] No retry attempt detected (not @RetryingTest), returning 0");
        return 0;
    }
    
    private static class RetryInfo {
        final String cleanName;
        final int attempt;
        
        RetryInfo(String cleanName, int attempt) {
            this.cleanName = cleanName;
            this.attempt = attempt;
        }
    }
}
