package com.m00nreport.reporter.fixtures;

import com.m00nreport.reporter.M00NExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suites that exist to be run BY a test, not as one.
 *
 * <p>They are deliberately outside the build's own test scan (see the exclude on the test task):
 * one of them fails on purpose, and a fixture that fails the build it is meant to be studied by
 * is useless. TeardownOutcomeTest runs them through the JUnit launcher and reads what the
 * reporter sent.</p>
 */
public final class TeardownFixtures {

    private TeardownFixtures() {
    }

    /** The body returns, then teardown fails the test. */
    @ExtendWith(M00NExtension.class)
    public static class PassingBodyFailingTeardown {
        @Test
        public void bodyPasses() {
            assertTrue(true);
        }

        @AfterEach
        public void teardownFails() {
            throw new IllegalStateException("cleanup assertion failed");
        }
    }

    /** The other direction: deferring the pass must not lose it. */
    @ExtendWith(M00NExtension.class)
    public static class EverythingPasses {
        @Test
        public void bodyPasses() {
            assertTrue(true);
        }

        @AfterEach
        public void teardownPasses() {
            assertTrue(true);
        }
    }

    /** The failure path, which still reports from inside the interceptor. */
    @ExtendWith(M00NExtension.class)
    public static class FailingBody {
        @Test
        public void bodyFails() {
            throw new AssertionError("the body itself failed");
        }
    }
}
