package com.m00nreport.reporter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * HTTP client for M00N Report ingest API.
 * Supports retry logic and multipart file uploads.
 */
public class M00NHttpClient {
    
    private static final Logger log = LoggerFactory.getLogger(M00NHttpClient.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private final OkHttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final Gson gson;
    private final int maxRetries;
    private final boolean debug;
    
    public M00NHttpClient(M00NConfig config) {
        String serverUrl = config.getServerUrl();
        
        // Validate URL
        if (serverUrl == null || serverUrl.isEmpty()) {
            throw new IllegalArgumentException("Server URL cannot be null or empty");
        }
        try {
            new URL(serverUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid server URL: " + serverUrl, e);
        }
        
        this.baseUrl = serverUrl.replaceAll("/$", "");
        this.apiKey = config.getApiKey();
        this.maxRetries = config.getMaxRetries();
        this.debug = config.isDebug();
        
        this.client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofMillis(config.getTimeout()))
            .writeTimeout(Duration.ofMillis(config.getTimeout()))
            .retryOnConnectionFailure(true)
            .connectionPool(new ConnectionPool(5, 60, TimeUnit.SECONDS))
            .build();
        
        this.gson = new GsonBuilder()
            .serializeNulls()
            .create();
    }
    
    /**
     * POST JSON data to the specified path with retry logic.
     */
    public JsonObject post(String path, Object body) throws IOException {
        String json = gson.toJson(body);
        if (debug) {
            log.debug("POST {} - Body: {}", path, json);
        }
        
        RequestBody requestBody = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
            .url(baseUrl + path)
            .header("Content-Type", "application/json")
            .header("X-API-Key", apiKey)
            .post(requestBody)
            .build();
        
        return executeWithRetry(request, path);
    }
    
    /**
     * POST JSON data without waiting for response (fire-and-forget).
     */
    public void postAsync(String path, Object body) {
        String json = gson.toJson(body);
        if (debug) {
            log.debug("POST (async) {} - Body: {}", path, json);
        }
        
        RequestBody requestBody = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
            .url(baseUrl + path)
            .header("Content-Type", "application/json")
            .header("X-API-Key", apiKey)
            .post(requestBody)
            .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (debug) {
                    log.warn("Async POST {} failed: {}", path, e.getMessage());
                }
            }
            
            @Override
            public void onResponse(Call call, Response response) {
                response.close();
            }
        });
    }
    
    /**
     * Upload file via multipart form.
     */
    public JsonObject uploadFile(String path, Map<String, String> fields, File file, 
                                  String filename, String contentType) throws IOException {
        MultipartBody.Builder builder = new MultipartBody.Builder()
            .setType(MultipartBody.FORM);
        
        // Add form fields
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            builder.addFormDataPart(entry.getKey(), entry.getValue());
        }
        
        // Add file
        MediaType mediaType = MediaType.parse(contentType);
        builder.addFormDataPart("file", filename, RequestBody.create(file, mediaType));
        
        Request request = new Request.Builder()
            .url(baseUrl + path)
            .header("X-API-Key", apiKey)
            .post(builder.build())
            .build();
        
        return executeWithRetry(request, path);
    }
    
    /**
     * Upload byte array via multipart form.
     */
    public JsonObject uploadBytes(String path, Map<String, String> fields, 
                                   byte[] data, String filename, String contentType) throws IOException {
        MultipartBody.Builder builder = new MultipartBody.Builder()
            .setType(MultipartBody.FORM);
        
        // Add form fields
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            builder.addFormDataPart(entry.getKey(), entry.getValue());
        }
        
        // Add file data
        MediaType mediaType = MediaType.parse(contentType);
        builder.addFormDataPart("file", filename, RequestBody.create(data, mediaType));
        
        Request request = new Request.Builder()
            .url(baseUrl + path)
            .header("X-API-Key", apiKey)
            .post(builder.build())
            .build();
        
        return executeWithRetry(request, path);
    }
    
    /**
     * Upload an AttachmentData to the server.
     */
    public void uploadAttachment(String path, String testId, String runId, 
                                  com.m00nreport.reporter.model.AttachmentData attachment) throws IOException {
        Map<String, String> fields = Map.of(
            "testId", testId,
            "runId", runId,
            "name", attachment.getName(),
            "contentType", attachment.getContentType()
        );
        
        if (attachment.isFileAttachment()) {
            uploadFile(path, fields, attachment.getFile(), 
                attachment.getName(), attachment.getContentType());
        } else if (attachment.getData() != null) {
            uploadBytes(path, fields, attachment.getData(), 
                attachment.getName(), attachment.getContentType());
        }
    }
    
    /**
     * Check if server is available.
     */
    public boolean healthCheck() {
        try {
            Request request = new Request.Builder()
                .url(baseUrl + "/healthz")
                .get()
                .build();
            
            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (IOException e) {
            if (debug) {
                log.warn("Health check failed: {}", e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * Execute request with exponential backoff retry.
     */
    private JsonObject executeWithRetry(Request request, String path) throws IOException {
        IOException lastException = null;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                try (Response response = client.newCall(request).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "{}";
                    
                    if (response.isSuccessful()) {
                        if (debug) {
                            log.debug("Response from {}: {}", path, responseBody);
                        }
                        return gson.fromJson(responseBody, JsonObject.class);
                    }
                    
                    // Check for permanent errors
                    JsonObject errorJson = gson.fromJson(responseBody, JsonObject.class);
                    String errorCode = errorJson.has("code") ? errorJson.get("code").getAsString() : null;
                    
                    if ("PROJECT_NOT_FOUND".equals(errorCode) || 
                        "API_KEY_REQUIRED".equals(errorCode) || 
                        "INVALID_API_KEY".equals(errorCode)) {
                        throw new IOException("Permanent error: " + errorCode);
                    }
                    
                    // HTTP 413 / attachment limit exceeded - permanent, do not retry
                    if (response.code() == 413 || "RUN_ATTACHMENT_LIMIT_EXCEEDED".equals(errorCode)) {
                        String message = errorJson.has("message") ? errorJson.get("message").getAsString() : "Run attachment limit exceeded";
                        log.warn("[M00NReporter] Attachment upload rejected: {}. Remaining uploads for this run may be skipped.", message);
                        throw new IOException("Permanent error: " + (errorCode != null ? errorCode : "HTTP_413") + " - " + message);
                    }
                    
                    // Transient error - retry
                    lastException = new IOException("HTTP " + response.code() + ": " + responseBody);
                }
            } catch (IOException e) {
                lastException = e;
                
                if (attempt < maxRetries) {
                    long delay = Math.min(1000L * (1L << (attempt - 1)), 5000L);
                    if (debug) {
                        log.warn("Retry {}/{} for {} after {}ms: {}", 
                            attempt, maxRetries, path, delay, e.getMessage());
                    }
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                }
            }
        }
        
        throw lastException != null ? lastException : new IOException("Unknown error");
    }
    
    /**
     * Shutdown the HTTP client.
     */
    public void shutdown() {
        client.dispatcher().executorService().shutdown();
        try {
            if (!client.dispatcher().executorService().awaitTermination(5, TimeUnit.SECONDS)) {
                client.dispatcher().executorService().shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        client.connectionPool().evictAll();
    }
}
