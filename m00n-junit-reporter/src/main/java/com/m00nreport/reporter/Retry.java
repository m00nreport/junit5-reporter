package com.m00nreport.reporter;

import java.lang.annotation.*;

/**
 * Marker annotation for retry tests.
 * 
 * @deprecated Use JUnit Pioneer's {@code @RetryingTest} instead.
 * This annotation has no effect - it was intended for retry functionality
 * that cannot be properly implemented via {@code TestExecutionExceptionHandler}.
 * 
 * <pre>{@code
 * // Instead of @Retry(3), use:
 * @RetryingTest(3)
 * void flakyTest() { ... }
 * }</pre>
 * 
 * <p>Add JUnit Pioneer dependency:</p>
 * <pre>{@code
 * testImplementation("org.junit-pioneer:junit-pioneer:2.3.0")
 * }</pre>
 * 
 * @see <a href="https://junit-pioneer.org/docs/retrying-test/">JUnit Pioneer RetryingTest</a>
 */
@Deprecated(since = "1.0.0", forRemoval = true)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Retry {
    /**
     * Maximum number of retry attempts.
     * @return the number of retries (default 2)
     * @deprecated This annotation has no effect. Use {@code @RetryingTest(maxAttempts = n)} instead.
     */
    @Deprecated
    int value() default 2;
}
