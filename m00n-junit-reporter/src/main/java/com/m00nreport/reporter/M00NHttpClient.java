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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Errors retrying cannot fix. Taken from the codes services/ingest actually
     * emits, not from the sibling reporters - the JS ones also list
     * PROJECT_NOT_FOUND, which the service has never sent.
     */
    private static final Set<String> PERMANENT_CODES = Set.of(
        "API_KEY_REQUIRED", "INVALID_API_KEY", "INVALID_API_KEY_FORMAT",
        "SUBSCRIPTION_INACTIVE", "RUN_ACCESS_DENIED", "RUN_NOT_FOUND",
        "RUN_NOT_ACTIVE", "ATTACHMENT_TOO_LARGE", "RUN_ATTACHMENT_LIMIT_EXCEEDED",
        "PRESIGN_BINDING_UNKNOWN", "UPLOAD_NOT_FOUND"
    );

    /**
     * The subset that condemns the whole run rather than one request. A wrong or
     * unauthorised credential will be just as wrong on the next test, so there is
     * nothing to gain by asking again 200 more times.
     *
     * Deliberately NOT everything permanent: an oversized attachment is a verdict
     * on that file, and treating it as fatal would let one big video silence the
     * reporting of every test after it.
     */
    private static final Set<String> FATAL_CODES = Set.of(
        "API_KEY_REQUIRED", "INVALID_API_KEY", "INVALID_API_KEY_FORMAT",
        "SUBSCRIPTION_INACTIVE"
    );

    /**
     * Consecutive failed calls before the client stops trying. Matches the other
     * reporters in the family. Only service-level failures count: a permanent
     * per-request verdict says nothing about whether the service is up.
     */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);

    /** A server verdict that another attempt cannot change. */
    private static class PermanentException extends IOException {
        private static final long serialVersionUID = 1L;
        private final boolean fatal;

        PermanentException(String message, boolean fatal) {
            super(message);
            this.fatal = fatal;
        }
    }
    
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
    /**
     * Liveness probe. Two paths, a validated body, and no authority over whether anything gets
     * reported - the caller uses it only to word a message.
     *
     * <p>/api/ingest/health is tried first because it is the edge-routable path in the published
     * contract and the one that survives a reverse proxy forwarding only /api/*. The body check is
     * the half that matters in production: the M00N Report cloud answers an unrouted path with the
     * single-page app shell and a 200, so a probe reading only the status passes against a service
     * it never reached - which made a fail-closed gate also fail-open, and is why this survived.</p>
     */
    public boolean healthCheck() {
        for (String path : new String[] { "/api/ingest/health", "/healthz" }) {
            try {
                Request request = new Request.Builder().url(baseUrl + path).get().build();
                try (Response response = client.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        continue;
                    }
                    String body = response.body() != null ? response.body().string() : "";
                    JsonObject parsed = asObject(body);
                    if (parsed.has("ok") && parsed.get("ok").isJsonPrimitive()
                            && parsed.get("ok").getAsJsonPrimitive().isBoolean()
                            && parsed.get("ok").getAsBoolean()) {
                        return true;
                    }
                }
            } catch (IOException e) {
                if (debug) {
                    log.warn("Health check failed on {}: {}", path, e.getMessage());
                }
            }
        }
        return false;
    }
    
    /**
     * Execute request with exponential backoff retry.
     */
    private JsonObject executeWithRetry(Request request, String path) throws IOException {
        if (circuitOpen.get()) {
            throw new IOException("Reporting disabled for this run after repeated failures");
        }

        IOException lastException = null;

        // At least one attempt, whatever maxRetries says. The loop bound used to be maxRetries
        // itself, so a configured 0 - which anyone would read as "try once, do not retry" - meant
        // the body never ran: the client reported a failure without ever contacting the server, and
        // every test in the suite was lost while the network was perfectly healthy.
        int attempts = Math.max(1, maxRetries);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                try (Response response = client.newCall(request).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "{}";

                    if (response.isSuccessful()) {
                        if (debug) {
                            log.debug("Response from {}: {}", path, responseBody);
                        }
                        consecutiveFailures.set(0);
                        return asObject(responseBody);
                    }

                    throw classifyError(response.code(), responseBody);
                }
            } catch (PermanentException e) {
                // Caught BEFORE the IOException handler below, which is the whole
                // point. This used to be an ordinary IOException raised inside the
                // try, so the retry handler one line down caught the signal meant
                // to bypass it, slept, and asked again. An INVALID_API_KEY cost
                // three attempts and three seconds of sleeping on every call for
                // the whole suite.
                if (e.fatal) {
                    openCircuit(e.getMessage());
                }
                throw e;
            } catch (IOException e) {
                lastException = e;

                if (attempt < attempts) {
                    long delay = Math.min(1000L * (1L << (attempt - 1)), 5000L);
                    if (debug) {
                        log.warn("Retry {}/{} for {} after {}ms: {}",
                            attempt, attempts, path, delay, e.getMessage());
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

        int failures = consecutiveFailures.incrementAndGet();
        if (failures == 1) {
            // The first failure is the one worth saying out loud. Warning only at
            // the threshold means a suite shorter than five calls against a dead
            // server says nothing at all.
            log.warn("[M00NReporter] Cannot reach the reporting service: {}. Tests continue regardless.",
                lastException != null ? lastException.getMessage() : "unknown error");
        }
        if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
            openCircuit(failures + " consecutive failures");
        }

        throw lastException != null ? lastException : new IOException("Unknown error");
    }

    /**
     * A response body as a JSON object, whatever the server actually sent.
     *
     * <p>gson.fromJson(body, JsonObject.class) raises JsonSyntaxException on anything that is not
     * an object - a JSON string, a number, an array, a plain-text body. That exception is
     * UNCHECKED, so it is not an IOException, it misses every retry and catch in this class, and it
     * propagates out of the reporter into the user's test. This was guarded on the error path and
     * not on the success path, which is the one that runs on every healthy call.</p>
     */
    private JsonObject asObject(String responseBody) {
        try {
            JsonObject parsed = gson.fromJson(responseBody, JsonObject.class);
            return parsed != null ? parsed : new JsonObject();
        } catch (RuntimeException e) {
            if (debug) {
                log.debug("Response was not a JSON object: {}", responseBody);
            }
            return new JsonObject();
        }
    }

    /**
     * Decide whether a non-2xx response is worth another attempt.
     *
     * Parsing is defensive because the body is not always ours. A proxy or CDN
     * answering 413 or 502 sends HTML or plain text, and gson throws an unchecked
     * JsonSyntaxException on it - which is not an IOException, so it escaped the
     * retry handler entirely and propagated out of the reporter.
     */
    private IOException classifyError(int status, String responseBody) {
        String errorCode = null;
        String message = null;
        try {
            JsonObject errorJson = gson.fromJson(responseBody, JsonObject.class);
            if (errorJson != null) {
                if (errorJson.has("code") && errorJson.get("code").isJsonPrimitive()) {
                    errorCode = errorJson.get("code").getAsString();
                }
                if (errorJson.has("message") && errorJson.get("message").isJsonPrimitive()) {
                    message = errorJson.get("message").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // Not JSON. The status code still classifies it.
        }

        // The attachment limit is signalled by status alone on some paths, so this
        // rule must not depend on the body parsing into anything.
        if (status == 413) {
            log.warn("[M00NReporter] Attachment upload rejected: {}. Remaining uploads for this run may be skipped.",
                message != null ? message : "payload too large");
            return new PermanentException(
                "Permanent error: " + (errorCode != null ? errorCode : "HTTP_413"), false);
        }

        if (errorCode != null && PERMANENT_CODES.contains(errorCode)) {
            return new PermanentException("Permanent error: " + errorCode,
                FATAL_CODES.contains(errorCode));
        }

        return new IOException("HTTP " + status + ": " + responseBody);
    }

    /** Stop asking. Said once, because a line per remaining test is noise. */
    private void openCircuit(String reason) {
        if (circuitOpen.compareAndSet(false, true)) {
            log.warn("[M00NReporter] Reporting disabled for the rest of this run ({}). Tests continue regardless.",
                reason);
        }
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
