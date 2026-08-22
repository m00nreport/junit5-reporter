package com.m00nreport.reporter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Whether a throwable that ended a test means the test was skipped or failed.
 *
 * The rule existed in two places and only one of them ran. The method interceptor caught every
 * throwable, reported "failed", and set the already-reported flag; the testAborted callback had
 * the correct version and was therefore unreachable. Every skipped test in every Java suite was
 * reported as a failure.
 *
 * Found by the canonical scenario set against a live stack: the `skipped` scenario, which uses
 * Assumptions.assumeTrue(false), arrived with status "failed" while the same scenario arrived as
 * "skipped" from all four other reporters.
 *
 * The retry case is what makes this more than a one-line check. A retry extension reports a failed
 * ATTEMPT through TestAbortedException as well, so the exception type alone cannot decide it.
 */
class M00NExtensionClassificationTest {

    @Test
    @DisplayName("an abandoned assumption is a skip")
    void abandonedAssumptionIsSkipped() {
        // The exact shape Assumptions.assumeTrue(false, "reason") produces.
        var aborted = new TestAbortedException("Assumption failed: canonical skip");

        assertEquals("skipped", M00NExtension.classifyThrowable(aborted));
    }

    @Test
    @DisplayName("an assumption with no message is still a skip")
    void bareAssumptionIsSkipped() {
        assertEquals("skipped", M00NExtension.classifyThrowable(new TestAbortedException()));
    }

    @Test
    @DisplayName("a retry attempt wrapped in TestAbortedException is a failure")
    void wrappedFailureIsFailed() {
        // JUnit Pioneer's @RetryingTest wraps the real failure, so the cause is the tell.
        var wrapped = new TestAbortedException("attempt 1 failed",
            new AssertionError("expected 2 but was 3"));

        assertEquals("failed", M00NExtension.classifyThrowable(wrapped));
    }

    @Test
    @DisplayName("an aborted exception whose message is not about an assumption is a failure")
    void unrelatedMessageIsFailed() {
        assertEquals("failed",
            M00NExtension.classifyThrowable(new TestAbortedException("retry 2 of 3 did not pass")));
    }

    @Test
    @DisplayName("an ordinary assertion failure is a failure")
    void assertionErrorIsFailed() {
        assertEquals("failed", M00NExtension.classifyThrowable(new AssertionError("nope")));
    }

    @Test
    @DisplayName("any other exception is a failure")
    void thrownExceptionIsFailed() {
        assertEquals("failed",
            M00NExtension.classifyThrowable(new IllegalStateException("canonical thrown error")));
    }

    @Test
    @DisplayName("no throwable at all is a skip, not a failure")
    void nullIsSkipped() {
        // testAborted can be called with null. Defaulting to "failed" there would invent a failure
        // out of an absence, which is the worse of the two ways to be wrong.
        assertEquals("skipped", M00NExtension.classifyThrowable(null));
    }

    // --- a display name that is only an invocation index -------------------------
    //
    // JUnit Pioneer's @RetryingTest is a test template, and the display-name resolver prefers an
    // invocation's name over the method's @DisplayName - right for @ParameterizedTest, where the
    // invocation name carries the arguments, wrong for Pioneer, which names its invocations "[1]"
    // and "[2]". Every retried test arrived in the dashboard called "[1]", including for anyone
    // following this reporter's own advice to use @RetryingTest instead of its deprecated @Retry.
    //
    // Found by the canonical scenario set: three scenarios lost their names entirely.

    @Test
    @DisplayName("an index alone is not a name")
    void bareIndexIsRecognised() {
        assertEquals(true, M00NExtension.isBareInvocationIndex("[1]"));
        assertEquals(true, M00NExtension.isBareInvocationIndex("[2]"));
        assertEquals(true, M00NExtension.isBareInvocationIndex("[10]"));
        assertEquals(true, M00NExtension.isBareInvocationIndex("  [3]  "));
    }

    @Test
    @DisplayName("a parameterized invocation name is left alone")
    void parameterisedNamesAreKept() {
        // These carry the arguments, which is the whole reason the resolver prefers them.
        assertEquals(false, M00NExtension.isBareInvocationIndex("[chromium]"));
        assertEquals(false, M00NExtension.isBareInvocationIndex("[1] firefox"));
        assertEquals(false, M00NExtension.isBareInvocationIndex("1: login with admin"));
        assertEquals(false, M00NExtension.isBareInvocationIndex("[TC-1]"));
    }

    @Test
    @DisplayName("nothing that is not a bracketed number counts")
    void hostileInput() {
        assertEquals(false, M00NExtension.isBareInvocationIndex(null));
        assertEquals(false, M00NExtension.isBareInvocationIndex(""));
        assertEquals(false, M00NExtension.isBareInvocationIndex("[]"));
        assertEquals(false, M00NExtension.isBareInvocationIndex("[ ]"));
        assertEquals(false, M00NExtension.isBareInvocationIndex("1"));
        assertEquals(false, M00NExtension.isBareInvocationIndex("[1"));
        assertEquals(false, M00NExtension.isBareInvocationIndex("1]"));
    }

    // --- which annotations mean a retry ------------------------------------------
    //
    // Matched by simple name so the reporter takes no compile dependency on JUnit Pioneer, which
    // is optional for consumers. The list is small and worth pinning: widening it would make every
    // @ParameterizedTest invocation index look like a retry attempt.

    @Test
    @DisplayName("the retrying annotations are recognised by name")
    void retryingAnnotationsRecognised() {
        assertEquals(true, M00NExtension.isRetryingAnnotationName("RetryingTest"));
        assertEquals(true, M00NExtension.isRetryingAnnotationName("Retry"));
    }

    @Test
    @DisplayName("nothing else counts, least of all a parameterized test")
    void otherAnnotationsAreNotRetries() {
        assertEquals(false, M00NExtension.isRetryingAnnotationName("ParameterizedTest"));
        assertEquals(false, M00NExtension.isRetryingAnnotationName("RepeatedTest"));
        assertEquals(false, M00NExtension.isRetryingAnnotationName("Test"));
        assertEquals(false, M00NExtension.isRetryingAnnotationName("retryingtest"));
        assertEquals(false, M00NExtension.isRetryingAnnotationName(""));
        assertEquals(false, M00NExtension.isRetryingAnnotationName(null));
    }
}
