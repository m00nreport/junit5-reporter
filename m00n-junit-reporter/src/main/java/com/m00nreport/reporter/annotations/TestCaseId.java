package com.m00nreport.reporter.annotations;

import java.lang.annotation.*;

/**
 * Links one or more M00N Report test cases to a JUnit 5 test method or class.
 *
 * <p>Values are M00N case numbers (the numeric part of the {@code TC-N} display ID).
 * The reporter rewrites the test name with {@code [TC-N]} markers and the ingest
 * service resolves them to the underlying case IDs.</p>
 *
 * <h2>Single case</h2>
 * <pre>{@code
 * @Test
 * @TestCaseId("634207")
 * void homepageLoads() { ... }
 * }</pre>
 *
 * <h2>Multiple cases covered by one test</h2>
 * <pre>{@code
 * @Test
 * @TestCaseId("634199, 634203, 634205")
 * void smokeCoversThreeCases() { ... }
 * }</pre>
 *
 * <h2>Parametrized — one case per invocation</h2>
 * <pre>{@code
 * @ParameterizedTest
 * @ValueSource(strings = {"a", "b", "c", "d", "e", "f"})
 * @TestCaseId(parametrized = true,
 *             value = "634199, 634203, 634205, 634206, 377482, 2878215")
 * void perInvocationLinks(String input) { ... }
 * // invocation 1 → 634199, invocation 2 → 634203, ...
 * }</pre>
 *
 * <h2>Class-level fallback</h2>
 * <pre>{@code
 * @TestCaseId("999999")
 * class CheckoutSuite {
 *     @Test void a() { ... }  // links to 999999
 *
 *     @Test
 *     @TestCaseId("111")      // method overrides class
 *     void b() { ... }
 * }
 * }</pre>
 *
 * <p>Method-level annotations fully override class-level (no merge).</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TestCaseId {

    /**
     * One or more M00N case numbers as a comma-separated string.
     * Whitespace around values is ignored.
     */
    String value() default "";

    /**
     * When {@code true} and the annotated method is a test template
     * (e.g. {@code @ParameterizedTest}), invocation N is linked to
     * {@code value[N-1]}. Otherwise all values link to every invocation.
     */
    boolean parametrized() default false;
}
