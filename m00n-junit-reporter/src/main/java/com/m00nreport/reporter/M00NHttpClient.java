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
import java.util.regex.Pattern;

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

    /** A bare media type: one token, a slash, one token, and nothing else. */
    private static final Pattern TYPE_SUBTYPE =
        Pattern.compile("[A-Za-z0-9!#$%&'*+.^_`|~-]+/[A-Za-z0-9!#$%&'*+.^_`|~-]+");

    private static final MediaType OCTET_STREAM = MediaType.get("application/octet-stream");

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
            // The API key travels in X-API-Key. A client drops the standard Authorization header
            // when a redirect crosses to another host and keeps every custom one, so a followed
            // redirect would hand the key to whatever host the Location names. The ingest API does
            // not redirect, so one is reported as an error instead.
            .followRedirects(false)
            .followSslRedirects(false)
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
     * The media type a multipart part may carry, given what an attachment declares.
     *
     * <p>The part header is written from this string as it stands, and a quoted parameter value may
     * hold a CR or an LF, which ends the header and turns the rest into an injected line. Only a
     * bare type/subtype is sent; anything else uploads as application/octet-stream, which is what
     * the server records for a part that declares no type.</p>
     */
    static MediaType safeMediaType(String contentType) {
        if (contentType == null || !TYPE_SUBTYPE.matcher(contentType).matches()) {
            return OCTET_STREAM;
        }
        MediaType parsed = MediaType.parse(contentType);
        return parsed != null ? parsed : OCTET_STREAM;
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
        builder.addFormDataPart("file", filename, RequestBody.create(file, safeMediaType(contentType)));
        
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
        builder.addFormDataPart("file", filename, RequestBody.create(data, safeMediaType(contentType)));
        
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
     * Liveness probe. Advisory only: the caller uses the result to word a message, not to decide
     * whether anything gets reported.
     *
     * <p>/api/ingest/health is tried first as the edge-routable path in the published contract,
     * the one that survives a reverse proxy forwarding only /api/*. The body is checked as well
     * as the status because an unrouted path can still answer 200 with a single-page app
     * shell.</p>
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

        // At least one attempt, whatever maxRetries says: a configured 0 reads as "try once, do
        // not retry", not "never contact the server".
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
                // Ordered before the IOException handler below: a permanent verdict has to
                // bypass the retry loop rather than be slept on and asked again.
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
            // Warned on the first failure rather than at the threshold: a suite shorter than
            // five calls against a dead server would otherwise say nothing at all.
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
     * <p>gson raises an UNCHECKED JsonSyntaxException on a body that is not an object - a JSON
     * string, a number, an array, a plain-text body. Unchecked means it is not an IOException, so
     * it would miss every retry and catch in this class and propagate into the user's test.</p>
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
     * Parsing is defensive because the body is not always ours: a proxy or CDN answering 413 or
     * 502 sends HTML or plain text, and gson's JsonSyntaxException is unchecked.
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

        // Redirects are not followed, so one means the configured URL does not point at the ingest
        // API. Another attempt would land on the same redirect.
        if (status >= 300 && status < 400) {
            return new PermanentException(
                "Server redirected (HTTP " + status + "). Check serverUrl: the ingest API does not redirect,"
                    + " and the API key is not sent to the redirect target.", false);
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
