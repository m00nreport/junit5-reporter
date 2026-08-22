package com.m00nreport.reporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Batches items and fires them in size-or-time bounded groups to an
 * {@link M00NHttpClient} endpoint. Mirrors the JS Playwright reporter's
 * {@code createBatcher} so a single HTTP request carries many step events
 * under parallel test load.
 *
 * <p>Flush triggers (whichever fires first):</p>
 * <ul>
 *   <li>buffer reaches {@code maxBatchSize} items</li>
 *   <li>{@code flushInterval} ms elapse since the first item arrived after the previous flush</li>
 * </ul>
 *
 * <p>Failure mode is fire-and-forget - if a batch POST drops, real-time
 * step updates are lost from the WebSocket stream but the test's
 * {@code /test/complete} payload still carries the full step list, so the
 * dashboard remains consistent.</p>
 *
 * <p>Thread-safe: {@link #add(Map)} may be called concurrently from many
 * test threads. The internal scheduler is single-threaded, so {@link #flush()}
 * never overlaps with itself. The HTTP fire happens outside the buffer lock
 * so producers never block on network I/O.</p>
 */
public class StepBatcher {

    private static final Logger log = LoggerFactory.getLogger(StepBatcher.class);

    private final M00NHttpClient httpClient;
    private final String path;
    private final int flushInterval;
    private final int maxBatchSize;

    private final Object lock = new Object();
    private final List<Map<String, Object>> buffer = new ArrayList<>();
    private ScheduledFuture<?> pendingFlush;

    private final ScheduledExecutorService scheduler;
    private volatile boolean shutdown = false;

    public StepBatcher(M00NHttpClient httpClient, String path, int flushInterval, int maxBatchSize) {
        this.httpClient = httpClient;
        this.path = path;
        this.flushInterval = flushInterval;
        this.maxBatchSize = maxBatchSize;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "m00n-step-batcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Append an item to the buffer. May trigger an immediate flush if the
     * size threshold is hit, otherwise schedules a time-based flush.
     * Safe to call concurrently from many threads.
     */
    public void add(Map<String, Object> item) {
        if (shutdown) return;

        boolean flushNow = false;
        synchronized (lock) {
            buffer.add(item);
            if (buffer.size() >= maxBatchSize) {
                cancelPendingFlush();
                flushNow = true;
            } else if (pendingFlush == null) {
                try {
                    pendingFlush = scheduler.schedule(this::flush, flushInterval, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException ignored) {
                    // Scheduler already shut down - flush inline
                    flushNow = true;
                }
            }
        }

        if (flushNow) {
            flush();
        }
    }

    /**
     * Drain the buffer in one HTTP request. Safe to call from any thread.
     * No-op when buffer is empty.
     */
    public void flush() {
        List<Map<String, Object>> items;
        synchronized (lock) {
            cancelPendingFlush();
            if (buffer.isEmpty()) return;
            items = new ArrayList<>(buffer);
            buffer.clear();
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("items", items);
            httpClient.postAsync(path, payload);
            log.debug("[StepBatcher] Flushed {} items to {}", items.size(), path);
        } catch (Exception e) {
            // Fire-and-forget - never propagate to test threads
            log.debug("[StepBatcher] Flush failed: {}", e.getMessage());
        }
    }

    /**
     * Number of items currently buffered. Mostly useful for shutdown
     * logging / diagnostics.
     */
    public int getPending() {
        synchronized (lock) {
            return buffer.size();
        }
    }

    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Drain any pending items and stop the scheduler. Idempotent.
     * After shutdown, further {@link #add(Map)} calls are silently ignored.
     */
    public void shutdownGracefully() {
        if (shutdown) return;
        shutdown = true;
        flush();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Must be called while holding `lock`.
    private void cancelPendingFlush() {
        if (pendingFlush != null) {
            pendingFlush.cancel(false);
            pendingFlush = null;
        }
    }
}
