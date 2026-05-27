package com.m00nreport.reporter;

import com.m00nreport.reporter.annotations.Step;
import com.m00nreport.reporter.model.TestResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AspectJ aspect for automatically intercepting @Step annotated methods.
 * 
 * <p>This aspect enables automatic step tracking without requiring StepProxy
 * or PageFactory. Simply annotate methods with @Step and they will be
 * automatically tracked when called.</p>
 * 
 * <h2>Setup:</h2>
 * <p>Enable AspectJ load-time weaving by adding to your test JVM args:</p>
 * <pre>{@code
 * -javaagent:path/to/aspectjweaver.jar
 * }</pre>
 * 
 * <p>Or use compile-time weaving with AspectJ compiler.</p>
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * public class LoginPage {
 *     @Step("Navigate to login page")
 *     public void open() {
 *         // This will be automatically tracked as a step!
 *     }
 *     
 *     @Step("Opening the page {url}")
 *     public void openPage(String url) {
 *         // Placeholders are replaced with actual values!
 *         // Shows: "Opening the page https://example.com"
 *     }
 * }
 * }</pre>
 * 
 * <h2>Placeholder Syntax:</h2>
 * <ul>
 *   <li>{paramName} - replaced with parameter value by name</li>
 *   <li>{0}, {1}, {2} - replaced with parameter value by index</li>
 * </ul>
 */
@Aspect
public class M00NStepAspect {
    
    // Pattern to match placeholders: {paramName} or {0}, {1}, etc.
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^}]+)}");
    
    // Maximum length for argument strings before truncation
    private static final int MAX_ARG_LENGTH = 200;
    
    // Maximum array items to show before truncating
    private static final int MAX_ARRAY_ITEMS = 5;
    
    /**
     * Intercepts only method EXECUTION (not call) annotated with @Step.
     * Using execution() to avoid duplicate interception at both call-site and execution.
     * 
     * <p>Supports nested steps: when a @Step method calls another @Step method,
     * the inner step automatically gets a higher nesting level.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Step("Complete checkout")
     * void checkout() {           // nestingLevel = 0
     *     enterShipping();        // nestingLevel = 1
     *     enterPayment();         // nestingLevel = 1
     *     confirmOrder();         // nestingLevel = 1
     * }
     * }</pre>
     */
    @Around("execution(@com.m00nreport.reporter.annotations.Step * *(..))")
    public Object interceptStep(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        
        // Extract step title from annotation and resolve placeholders
        Step stepAnnotation = method.getAnnotation(Step.class);
        String stepTitle = extractStepTitle(stepAnnotation, method, signature, args);
        
        // Get current test result
        TestResult testResult = M00NStep.getCurrentTest();
        if (testResult == null) {
            // No active test, just proceed without tracking
            return joinPoint.proceed();
        }
        
        // Get current nesting level BEFORE creating the step
        int nestingLevel = M00NStep.getCurrentNestingLevel();
        
        // Create and track step with nesting level
        var step = testResult.addStep(stepTitle, "step");
        step.setNestingLevel(nestingLevel);
        
        long startTime = System.currentTimeMillis();
        
        // Increment nesting level for any nested @Step calls within this step
        M00NStep.incrementNestingLevel();
        
        try {
            Object result = joinPoint.proceed();
            step.setDuration(System.currentTimeMillis() - startTime);
            // Complete step as passed
            testResult.completeStep(step, true, null);
            return result;
        } catch (Throwable t) {
            step.setDuration(System.currentTimeMillis() - startTime);
            // Complete step as failed
            testResult.completeStep(step, false, t);
            throw t;
        } finally {
            // Decrement nesting level when exiting this step
            M00NStep.decrementNestingLevel();
        }
    }
    
    /**
     * Extracts step title from annotation, resolving any placeholders.
     * 
     * Supports:
     * - {paramName} - parameter by name (requires -parameters compiler flag)
     * - {0}, {1}, {2} - parameter by index
     * 
     * @param annotation The Step annotation
     * @param method The method being executed
     * @param signature The method signature (contains parameter names)
     * @param args The actual argument values
     * @return The resolved step title
     */
    private String extractStepTitle(Step annotation, Method method, 
                                     MethodSignature signature, Object[] args) {
        String title = annotation.value();
        if (title == null || title.isEmpty()) {
            title = humanize(method.getName());
        }
        
        // If no placeholders, return as-is
        if (!title.contains("{")) {
            return title;
        }
        
        // Get parameter names from signature (AspectJ provides these)
        String[] paramNames = signature.getParameterNames();
        
        // Resolve placeholders
        return resolvePlaceholders(title, paramNames, args);
    }
    
    /**
     * Resolves placeholders in the title string.
     * 
     * @param title The title with placeholders like {url} or {0}
     * @param paramNames The parameter names from method signature
     * @param args The actual argument values
     * @return The title with placeholders replaced by values
     */
    private String resolvePlaceholders(String title, String[] paramNames, Object[] args) {
        if (args == null || args.length == 0) {
            return title;
        }
        
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(title);
        StringBuilder result = new StringBuilder();
        
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = resolveSinglePlaceholder(placeholder, paramNames, args);
            // Escape special regex characters in replacement
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        
        return result.toString();
    }
    
    /**
     * Resolves a single placeholder to its value.
     */
    private String resolveSinglePlaceholder(String placeholder, String[] paramNames, Object[] args) {
        // Try numeric index first: {0}, {1}, {2}
        try {
            int index = Integer.parseInt(placeholder);
            if (index >= 0 && index < args.length) {
                return formatArgument(args[index]);
            }
        } catch (NumberFormatException ignored) {
            // Not a number, try by name
        }
        
        // Try by parameter name: {url}, {username}
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if (placeholder.equals(paramNames[i]) && i < args.length) {
                    return formatArgument(args[i]);
                }
            }
        }
        
        // Placeholder not resolved, return as-is
        return "{" + placeholder + "}";
    }
    
    /**
     * Formats an argument value for display.
     * Handles nulls, arrays, and long strings.
     */
    private String formatArgument(Object arg) {
        if (arg == null) {
            return "null";
        }
        
        // Handle arrays
        if (arg.getClass().isArray()) {
            if (arg instanceof Object[] objArray) {
                return formatArray(objArray);
            } else if (arg instanceof int[] intArray) {
                return formatPrimitiveArray(intArray);
            } else if (arg instanceof long[] longArray) {
                return formatPrimitiveArray(longArray);
            } else if (arg instanceof boolean[] boolArray) {
                return formatPrimitiveArray(boolArray);
            } else if (arg instanceof double[] doubleArray) {
                return formatPrimitiveArray(doubleArray);
            } else if (arg instanceof float[] floatArray) {
                return formatPrimitiveArray(floatArray);
            } else if (arg instanceof byte[] byteArray) {
                return formatPrimitiveArray(byteArray);
            } else if (arg instanceof char[] charArray) {
                return formatPrimitiveArray(charArray);
            } else if (arg instanceof short[] shortArray) {
                return formatPrimitiveArray(shortArray);
            }
        }
        
        String str = arg.toString();
        
        // Truncate very long strings (e.g., huge JSON payloads)
        if (str.length() > MAX_ARG_LENGTH) {
            return str.substring(0, MAX_ARG_LENGTH) + "...";
        }
        
        return str;
    }
    
    private String formatArray(Object[] array) {
        if (array.length == 0) return "[]";
        if (array.length > MAX_ARRAY_ITEMS) {
            return "[" + formatArgument(array[0]) + ", " + formatArgument(array[1]) + 
                   ", ... (" + array.length + " items)]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatArgument(array[i]));
        }
        return sb.append("]").toString();
    }
    
    /**
     * Generic primitive array formatter to avoid code duplication.
     * 
     * @param length Array length
     * @param elementFormatter Function that returns string for element at index
     * @return Formatted array string
     */
    private String formatPrimitiveArrayGeneric(int length, IntFunction<String> elementFormatter) {
        if (length == 0) return "[]";
        if (length > MAX_ARRAY_ITEMS) {
            return "[" + elementFormatter.apply(0) + ", " + elementFormatter.apply(1) + 
                   ", ... (" + length + " items)]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(elementFormatter.apply(i));
        }
        return sb.append("]").toString();
    }
    
    private String formatPrimitiveArray(int[] array) {
        return formatPrimitiveArrayGeneric(array.length, i -> String.valueOf(array[i]));
    }
    
    private String formatPrimitiveArray(long[] array) {
        return formatPrimitiveArrayGeneric(array.length, i -> String.valueOf(array[i]));
    }
    
    private String formatPrimitiveArray(boolean[] array) {
        return formatPrimitiveArrayGeneric(array.length, i -> String.valueOf(array[i]));
    }
    
    private String formatPrimitiveArray(double[] array) {
        return formatPrimitiveArrayGeneric(array.length, i -> String.valueOf(array[i]));
    }
    
    private String formatPrimitiveArray(float[] array) {
        return formatPrimitiveArrayGeneric(array.length, i -> String.valueOf(array[i]));
    }
    
    private String formatPrimitiveArray(byte[] array) {
        return formatPrimitiveArrayGeneric(array.length, i -> String.valueOf(array[i]));
    }
    
    private String formatPrimitiveArray(char[] array) {
        return formatPrimitiveArrayGeneric(array.length, i -> "'" + array[i] + "'");
    }
    
    private String formatPrimitiveArray(short[] array) {
        return formatPrimitiveArrayGeneric(array.length, i -> String.valueOf(array[i]));
    }
    
    /**
     * Converts camelCase method name to human-readable format.
     * Example: "clickLoginButton" → "Click login button"
     */
    private String humanize(String methodName) {
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
}
