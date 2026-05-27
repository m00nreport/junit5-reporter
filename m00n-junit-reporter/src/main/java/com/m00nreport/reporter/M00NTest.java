package com.m00nreport.reporter;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.*;

/**
 * Enables M00N Report integration for a test class.
 * 
 * <p>This annotation enables:</p>
 * <ul>
 *   <li>Automatic test lifecycle tracking (start/end)</li>
 *   <li>Step tracking via {@link StepProxy} and @Step annotation</li>
 *   <li>Attachment uploads (screenshots, videos, traces)</li>
 *   <li>Error tracking and reporting</li>
 * </ul>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * @M00NTest
 * class LoginTests extends BasePlaywrightTest {
 *     
 *     private LoginPage loginPage;
 *     
 *     @BeforeEach
 *     void init() {
 *         loginPage = StepProxy.create(LoginPage.class, new LoginPageImpl(page));
 *     }
 *     
 *     @Test
 *     void successfulLogin() {
 *         loginPage.open();
 *         loginPage.login("user", "pass");
 *         loginPage.verifyLoggedIn();
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith({M00NExtension.class, M00NStepExtension.class})
public @interface M00NTest {
}
