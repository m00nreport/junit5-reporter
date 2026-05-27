package com.m00nreport.reporter;

import com.m00nreport.reporter.annotations.Step;
import com.m00nreport.reporter.model.ErrorData;
import com.m00nreport.reporter.model.StepData;
import com.m00nreport.reporter.model.TestResult;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

/**
 * Creates proxy instances that automatically track @Step annotated method calls.
 * 
 * <p>This is the primary way to enable step tracking for Page Objects.
 * All methods annotated with {@link Step} will be automatically recorded
 * as test steps when called through the proxy.</p>
 * 
 * <h2>Usage Example:</h2>
 * <pre>{@code
 * // Define interface with step annotations
 * interface LoginPage {
 *     @Step("Navigate to login page")
 *     void open();
 *     
 *     @Step("Enter username")
 *     void enterUsername(String username);
 *     
 *     @Step  // Auto-generates title from method name
 *     void clickLoginButton();
 * }
 * 
 * // Create proxy instance
 * var loginPage = StepProxy.create(LoginPage.class, new LoginPageImpl(page));
 * 
 * // Call methods - automatically tracked as steps!
 * loginPage.open();
 * loginPage.enterUsername("admin");
 * loginPage.clickLoginButton();
 * }</pre>
 */
public final class StepProxy {
    
    private StepProxy() {} // Utility class
    
    /**
     * Creates a proxy that tracks @Step annotated methods.
     *
     * @param <T> the interface type
     * @param interfaceType the interface class with step annotations
     * @param implementation the actual implementation
     * @return proxied instance that tracks step execution
     */
    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> interfaceType, T implementation) {
        return (T) Proxy.newProxyInstance(
            interfaceType.getClassLoader(),
            new Class<?>[]{interfaceType},
            new StepHandler(implementation)
        );
    }
    
    /**
     * Invocation handler that intercepts method calls and tracks steps.
     */
    private record StepHandler(Object target) implements InvocationHandler {
        
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // Find the actual method on the target class to handle nested/non-public interfaces
            Method targetMethod = findTargetMethod(method);
            
            // Get step annotation if present (from interface method for annotations)
            var stepTitle = extractStepTitle(method);
            
            // If no @Step annotation, just invoke directly
            if (stepTitle.isEmpty()) {
                return targetMethod.invoke(target, args);
            }
            
            // Get current test result
            var testResult = M00NStep.getCurrentTest();
            if (testResult == null) {
                return targetMethod.invoke(target, args);
            }
            
            // Create and track step (this sends "append" event)
            var step = testResult.addStep(stepTitle.get(), "step");
            var startTime = System.currentTimeMillis();
            
            try {
                var result = targetMethod.invoke(target, args);
                step.setDuration(System.currentTimeMillis() - startTime);
                // Complete step as passed (this sends "end" event)
                testResult.completeStep(step, true, null);
                return result;
            } catch (Throwable t) {
                step.setDuration(System.currentTimeMillis() - startTime);
                var cause = t.getCause() != null ? t.getCause() : t;
                // Complete step as failed (this sends "end" event)
                testResult.completeStep(step, false, cause);
                throw cause;
            }
        }
        
        /**
         * Finds the corresponding method on the target implementation class.
         * This handles cases where the interface is nested/non-public.
         */
        private Method findTargetMethod(Method interfaceMethod) throws NoSuchMethodException {
            Method targetMethod = target.getClass().getMethod(
                interfaceMethod.getName(), 
                interfaceMethod.getParameterTypes()
            );
            targetMethod.setAccessible(true);
            return targetMethod;
        }
        
        /**
         * Extracts step title from @Step annotation or generates from method name.
         */
        private Optional<String> extractStepTitle(Method method) {
            var annotation = method.getAnnotation(Step.class);
            if (annotation == null) {
                return Optional.empty();
            }
            
            var title = annotation.value();
            if (title.isEmpty()) {
                title = humanize(method.getName());
            }
            return Optional.of(title);
        }
        
        /**
         * Converts camelCase method name to human-readable title.
         * Example: "clickLoginButton" → "Click login button"
         */
        private String humanize(String methodName) {
            var result = new StringBuilder();
            for (int i = 0; i < methodName.length(); i++) {
                var c = methodName.charAt(i);
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
    }
}
