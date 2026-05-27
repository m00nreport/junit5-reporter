package com.m00nreport.reporter;

import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit Platform Listener for run-level lifecycle.
 * 
 * <p>Registered via ServiceLoader (META-INF/services).</p>
 * 
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>testPlanExecutionStarted: Start the M00N run with total test count</li>
 *   <li>testPlanExecutionFinished: End the M00N run with final status</li>
 * </ul>
 * 
 * <p>Note: Test pass/fail counts are tracked by M00NReporter's atomic counters
 * to ensure thread-safety during parallel test execution.</p>
 */
public class M00NTestExecutionListener implements TestExecutionListener {
    
    private static final Logger log = LoggerFactory.getLogger(M00NTestExecutionListener.class);
    
    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        try {
            M00NReporter reporter = M00NReporter.getInstance();
            if (!reporter.isEnabled()) return;
            
            // Count test identifiers - includes both regular tests and test templates
            // isTest() returns false for @RetryingTest/@ParameterizedTest which are containers
            int regularTests = (int) testPlan.countTestIdentifiers(TestIdentifier::isTest);
            
            // Also count test templates (containers with children) - like @RetryingTest, @ParameterizedTest
            int templateTests = (int) testPlan.countTestIdentifiers(id -> 
                id.isContainer() && id.getSource().isPresent() && 
                id.getSource().get().toString().contains("Method"));
            
            int totalTests = regularTests + templateTests;
            log.info("[M00NReporter] Test count: {} regular + {} templates = {}", 
                regularTests, templateTests, totalTests);
            
            // Always start the run if we have any tests, or even if count is uncertain
            // The actual count will be tracked as tests are started
            reporter.startRun(Math.max(totalTests, 1));
            log.info("[M00NReporter] Started run");
        } catch (Exception e) {
            log.warn("[M00NReporter] Failed to start run: {}", e.getMessage());
        }
    }
    
    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        try {
            M00NReporter reporter = M00NReporter.getInstance();
            if (!reporter.isEnabled()) return;
            
            // Determine final status from reporter's thread-safe atomic counters
            String status;
            if (reporter.getTestsFailed() > 0) {
                status = "failed";
            } else if (reporter.getTestsPassed() > 0) {
                status = "passed";
            } else {
                status = "finished";
            }
            
            reporter.endRun(status);
        } catch (Exception e) {
            log.warn("[M00NReporter] Failed to end run: {}", e.getMessage());
        }
    }
}
