package com.m00nreport.reporter;

import com.m00nreport.reporter.annotations.Step;
import com.m00nreport.reporter.model.ErrorData;
import com.m00nreport.reporter.model.StepData;
import org.junit.jupiter.api.extension.*;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * JUnit 5 Extension that intercepts lifecycle methods and tracks @Step annotations.
 * 
 * <p>This extension intercepts @BeforeEach, @AfterEach, @BeforeAll, and @AfterAll
 * methods to track steps defined in test setup/teardown.</p>
 * 
 * <p>For tracking steps within test methods, use {@link StepProxy} with Page Objects.</p>
 */
public class M00NStepExtension implements InvocationInterceptor {
    
    @Override
    public void interceptBeforeAllMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> context,
                                         ExtensionContext extensionContext) throws Throwable {
        executeWithStepTracking(invocation, context.getExecutable());
    }
    
    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
                                          ReflectiveInvocationContext<Method> context,
                                          ExtensionContext extensionContext) throws Throwable {
        executeWithStepTracking(invocation, context.getExecutable());
    }
    
    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> context,
                                         ExtensionContext extensionContext) throws Throwable {
        executeWithStepTracking(invocation, context.getExecutable());
    }
    
    @Override
    public void interceptAfterAllMethod(Invocation<Void> invocation,
                                        ReflectiveInvocationContext<Method> context,
                                        ExtensionContext extensionContext) throws Throwable {
        executeWithStepTracking(invocation, context.getExecutable());
    }
    
    /**
     * Executes invocation with step tracking if @Step annotation is present.
     */
    private void executeWithStepTracking(Invocation<Void> invocation, Method method) throws Throwable {
        var stepTitle = extractStepTitle(method);
        
        if (stepTitle.isEmpty()) {
            invocation.proceed();
            return;
        }
        
        var testResult = M00NStep.getCurrentTest();
        if (testResult == null) {
            invocation.proceed();
            return;
        }
        
        var step = testResult.addStep(stepTitle.get(), "step");
        
        // Finished through completeStep, not by setting the fields here: addStep streams the
        // "append" event and only completeStep streams the matching "end" and measures the
        // duration from addStep's own stamp.
        try {
            invocation.proceed();
            testResult.completeStep(step, true, null);
        } catch (Throwable t) {
            testResult.completeStep(step, false, t);
            throw t;
        }
    }
    
    /**
     * Extracts step title from @Step annotation.
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
     * Converts camelCase to human-readable format.
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
