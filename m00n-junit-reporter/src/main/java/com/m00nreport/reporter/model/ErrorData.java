package com.m00nreport.reporter.model;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Represents an error/exception for M00N Report.
 * 
 * <p>Captures exception details including message, stack trace, and type name
 * for display in the test report.</p>
 */
public class ErrorData {
    
    private String message;
    private String stack;
    private String name;
    
    // For JSON deserialization
    public ErrorData() {}
    
    /**
     * Creates error data from a throwable.
     */
    public ErrorData(Throwable throwable) {
        if (throwable != null) {
            this.message = throwable.getMessage();
            this.name = throwable.getClass().getSimpleName();
            this.stack = getStackTraceAsString(throwable);
        }
    }
    
    /**
     * Creates error data from message and stack trace strings.
     */
    public ErrorData(String message, String stack) {
        this.message = message;
        this.stack = stack;
    }
    
    /**
     * Converts throwable stack trace to string using modern approach.
     */
    private String getStackTraceAsString(Throwable throwable) {
        var sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
    
    // Getters and setters
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public String getStack() { return stack; }
    public void setStack(String stack) { this.stack = stack; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
