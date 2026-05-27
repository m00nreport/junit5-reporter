package com.m00nreport.reporter.annotations;

import java.lang.annotation.*;

/**
 * Marks a method as a test step for M00N Report.
 * 
 * <p>When a method annotated with @Step is called, it will be automatically
 * tracked as a step in the test report with timing and status.</p>
 * 
 * <h2>Basic Usage:</h2>
 * <pre>{@code
 * public class LoginPage {
 *     @Step("Navigate to login page")
 *     public void open() { ... }
 *     
 *     @Step  // Uses method name: "Click login button"
 *     public void clickLoginButton() { ... }
 * }
 * }</pre>
 * 
 * <h2>Parameter Placeholders:</h2>
 * <p>Use placeholders to include method argument values in the step title:</p>
 * <pre>{@code
 * @Step("Opening the page {url}")
 * public void openPage(String url) { ... }
 * // Shows: "Opening the page https://example.com"
 * 
 * @Step("Login as {0} with password {1}")
 * public void login(String user, String pass) { ... }
 * // Shows: "Login as admin with password ***"
 * }</pre>
 * 
 * <h3>Placeholder Syntax:</h3>
 * <ul>
 *   <li><code>{paramName}</code> - parameter by name (e.g., {url}, {username})</li>
 *   <li><code>{0}, {1}, {2}</code> - parameter by index</li>
 * </ul>
 * 
 * <p><b>Note:</b> For parameter name resolution to work, compile with
 * <code>-parameters</code> flag or use index-based placeholders.</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Step {
    
    /**
     * Step description/title with optional placeholders.
     * 
     * <p>If empty, the method name will be converted to a readable format.
     * Example: "clickLoginButton" → "Click login button"</p>
     * 
     * <p>Placeholders like {paramName} or {0} will be replaced with
     * actual argument values at runtime.</p>
     * 
     * @return the step title template
     */
    String value() default "";
}
