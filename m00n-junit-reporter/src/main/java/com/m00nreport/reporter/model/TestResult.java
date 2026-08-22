package com.m00nreport.reporter.model;

import com.google.gson.annotations.SerializedName;
import com.m00nreport.reporter.M00NReporter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Represents a test result for M00N Report.
 */
public class TestResult {
    
    @SerializedName("testId")
    private String testId;
    
    @SerializedName("runId")
    private String runId;
    
    @SerializedName("filePath")
    private String filePath;
    
    @SerializedName("titlePath")
    private List<String> titlePath;
    
    @SerializedName("retry")
    private int retry;
    
    @SerializedName("startedAt")
    private String startedAt;
    
    @SerializedName("endedAt")
    private String endedAt;
    
    @SerializedName("status")
    private String status;
    
    @SerializedName("duration")
    private Long duration;
    
    @SerializedName("error")
    private ErrorData error;
    
    @SerializedName("steps")
    private List<StepData> steps;
    
    @SerializedName("attachments")
    private List<AttachmentData> attachments;
    
    // Transient fields (not serialized)
    private transient long startTimeMs;
    private transient int stepIndex = 0;
    
    public TestResult() {
        this.testId = UUID.randomUUID().toString();
        this.titlePath = new ArrayList<>();
        this.steps = new ArrayList<>();
        this.attachments = new ArrayList<>();
        this.retry = 0;
    }
    
    /**
     * Start the test result.
     * 
     * <p>TitlePath format (matches Playwright JS for UI compatibility):</p>
     * <ul>
     *   <li>[0] = project/browser (e.g., "chromium")</li>
     *   <li>[1] = shard/worker info (empty for Java)</li>
     *   <li>[2] = file path (e.g., "com/m00nreport/tests/MyTest.java")</li>
     *   <li>[3] = suite name (class display name)</li>
     *   <li>[4] = test name (method display name)</li>
     * </ul>
     * 
     * @param runId The run ID
     * @param suiteDisplayName Display name for suite (from @DisplayName or class name)
     * @param testDisplayName Display name for test (from @DisplayName or method name)
     * @param actualClassName Full class name for file path (e.g., com.example.MyTest)
     */
    public void start(String runId, String suiteDisplayName, String testDisplayName, String actualClassName) {
        this.runId = runId;
        this.startTimeMs = System.currentTimeMillis();
        this.startedAt = Instant.now().toString();
        this.status = "running";
        
        // Extract file path from actual class name
        if (actualClassName != null) {
            this.filePath = actualClassName.replace('.', '/') + ".java";
        }
        
        // Build titlePath matching Playwright format for UI compatibility:
        // [project, shard, filePath, suiteName, testName]
        this.titlePath = new ArrayList<>();
        this.titlePath.add("java");                                    // [0] project
        this.titlePath.add("");                                        // [1] shard (empty)
        this.titlePath.add(this.filePath != null ? this.filePath : ""); // [2] file path
        this.titlePath.add(suiteDisplayName != null ? suiteDisplayName : ""); // [3] suite
        this.titlePath.add(testDisplayName != null ? testDisplayName : "");   // [4] test
    }
    
    public void end(String status, Throwable error) {
        this.endedAt = Instant.now().toString();
        this.duration = System.currentTimeMillis() - startTimeMs;
        this.status = status;
        
        if (error != null) {
            this.error = new ErrorData(error);
        }
    }
    
    /** Start times for steps added through addStep, so completeStep can measure them. */
    private final java.util.Map<StepData, Long> stepStartMillis = new java.util.concurrent.ConcurrentHashMap<>();

    public StepData addStep(String title, String category) {
        StepData step = new StepData(title, category, stepIndex++);
        // Stamped so completeStep can measure. Steps added through this API always reported a null
        // duration, which the UI renders as a dash - indistinguishable from a step that genuinely
        // took no time, and useless for finding the slow one.
        stepStartMillis.put(step, System.currentTimeMillis());
        // Honour the current nesting level. The @Step aspect maintains it around every annotated
        // method, but this - the API a user reaches for when adding a step by hand - created every
        // step at level 0, so a manually built hierarchy rendered as a flat list however it was
        // written. Wrap the inner calls in M00NStep.incrementNestingLevel/decrementNestingLevel to
        // build one.
        step.setNestingLevel(com.m00nreport.reporter.M00NStep.getCurrentNestingLevel());
        steps.add(step);
        
        // Stream step to server in real-time
        M00NReporter.getInstance().streamStep(this, step, "append");
        
        return step;
    }
    
    public void completeStep(StepData step, boolean passed, Throwable error) {
        step.setStatus(passed ? "passed" : "failed");
        Long startedAt = stepStartMillis.remove(step);
        if (startedAt != null) {
            step.setDuration(Math.max(0L, System.currentTimeMillis() - startedAt));
        }
        if (error != null) {
            step.setError(new ErrorData(error));
        }
        
        // Stream step completion to server
        M00NReporter.getInstance().streamStep(this, step, "end");
    }
    
    public void addAttachment(AttachmentData attachment) {
        attachments.add(attachment);
    }
    
    // Getters and setters
    public String getTestId() { return testId; }
    public void setTestId(String testId) { this.testId = testId; }
    
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    
    public List<String> getTitlePath() { return titlePath; }
    public void setTitlePath(List<String> titlePath) { this.titlePath = titlePath; }
    
    public int getRetry() { return retry; }
    public void setRetry(int retry) { this.retry = retry; }
    
    public String getStartedAt() { return startedAt; }
    public String getEndedAt() { return endedAt; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Long getDuration() { return duration; }
    
    public ErrorData getError() { return error; }
    
    public List<StepData> getSteps() { return steps; }
    
    public List<AttachmentData> getAttachments() { return attachments; }
}
