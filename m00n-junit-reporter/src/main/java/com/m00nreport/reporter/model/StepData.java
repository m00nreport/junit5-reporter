package com.m00nreport.reporter.model;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a test step for M00N Report.
 * 
 * <p>Steps are the building blocks of test execution, representing
 * individual actions or verifications within a test.</p>
 */
public class StepData {
    
    private String title;
    private String category;
    private String status;
    private Long duration;
    private ErrorData error;
    
    @SerializedName("nestingLevel")
    private int nestingLevel;
    
    private int index;
    
    // For JSON deserialization
    public StepData() {}
    
    /**
     * Creates a new step with the given title and index.
     */
    public StepData(String title, String category, int index) {
        this.title = title;
        this.category = category;
        this.index = index;
        this.status = "running";
        this.nestingLevel = 0;
    }
    
    // Fluent setters for cleaner code
    
    public StepData withStatus(String status) {
        this.status = status;
        return this;
    }
    
    public StepData withDuration(Long duration) {
        this.duration = duration;
        return this;
    }
    
    public StepData withError(ErrorData error) {
        this.error = error;
        return this;
    }
    
    // Standard getters and setters
    
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Long getDuration() { return duration; }
    public void setDuration(Long duration) { this.duration = duration; }
    
    public ErrorData getError() { return error; }
    public void setError(ErrorData error) { this.error = error; }
    
    public int getNestingLevel() { return nestingLevel; }
    public void setNestingLevel(int nestingLevel) { this.nestingLevel = nestingLevel; }
    
    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }
}
