package com.m00nreport.reporter.model;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents an attachment for M00N Report.
 * 
 * <p>Supports various attachment types including screenshots, videos, and trace files.
 * Uses static factory methods for creating different attachment types.</p>
 * 
 * <p>Maximum attachment size is 200MB. Files exceeding this limit will be skipped
 * with a warning log message. This matches the server-side limit.</p>
 */
public class AttachmentData {
    
    private static final Logger log = LoggerFactory.getLogger(AttachmentData.class);
    
    /** Maximum attachment file size: 200MB (matches server-side limit) */
    public static final long MAX_ATTACHMENT_SIZE = 200L * 1024 * 1024;
    
    private final String id;
    private final String name;
    private final String contentType;
    private final byte[] data;
    private final File file;
    private final long size;
    
    private AttachmentData(String name, String contentType, byte[] data, File file) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.contentType = contentType;
        this.data = data;
        this.file = file;
        this.size = data != null ? data.length : (file != null ? file.length() : 0);
    }
    
    // Static factory methods
    
    /**
     * Creates attachment from byte array.
     * Returns null if data exceeds {@link #MAX_ATTACHMENT_SIZE}.
     */
    public static AttachmentData fromBytes(String name, byte[] data, String contentType) {
        if (data != null && data.length > MAX_ATTACHMENT_SIZE) {
            log.warn("[M00NReporter] Attachment skipped: \"{}\" ({} MB) exceeds {} MB limit",
                name, String.format("%.1f", data.length / (1024.0 * 1024.0)),
                MAX_ATTACHMENT_SIZE / (1024 * 1024));
            return null;
        }
        return new AttachmentData(name, contentType, data, null);
    }
    
    /**
     * Creates attachment from file.
     * Returns null if file exceeds {@link #MAX_ATTACHMENT_SIZE}.
     */
    public static AttachmentData fromFile(String name, File file, String contentType) {
        if (file != null && file.exists() && file.length() > MAX_ATTACHMENT_SIZE) {
            log.warn("[M00NReporter] Attachment skipped: \"{}\" ({} MB) exceeds {} MB limit. File: {}",
                name, String.format("%.1f", file.length() / (1024.0 * 1024.0)),
                MAX_ATTACHMENT_SIZE / (1024 * 1024), file.getAbsolutePath());
            return null;
        }
        return new AttachmentData(name, contentType, null, file);
    }
    
    /**
     * Creates attachment from path.
     * Returns null if file exceeds {@link #MAX_ATTACHMENT_SIZE}.
     */
    public static AttachmentData fromPath(String name, Path path, String contentType) {
        return fromFile(name, path.toFile(), contentType);
    }
    
    /**
     * Creates screenshot attachment (PNG).
     */
    public static AttachmentData screenshot(String name, byte[] data) {
        var fileName = name.endsWith(".png") ? name : name + ".png";
        return fromBytes(fileName, data, "image/png");
    }
    
    /**
     * Creates trace attachment (ZIP) from path.
     */
    public static AttachmentData trace(Path tracePath) {
        return fromPath("trace.zip", tracePath, "application/zip");
    }
    
    /**
     * Creates trace attachment (ZIP) from bytes.
     */
    public static AttachmentData trace(String name, byte[] data) {
        var fileName = name.endsWith(".zip") ? name : name + ".zip";
        return fromBytes(fileName, data, "application/zip");
    }
    
    /**
     * Creates video attachment (WebM) from path.
     */
    public static AttachmentData video(Path videoPath) {
        return fromPath("video.webm", videoPath, "video/webm");
    }
    
    /**
     * Creates video attachment (WebM) from bytes.
     */
    public static AttachmentData video(String name, byte[] data) {
        var fileName = name.endsWith(".webm") ? name : name + ".webm";
        return fromBytes(fileName, data, "video/webm");
    }
    
    // Getters
    
    public String getId() { return id; }
    public String getName() { return name; }
    public String getContentType() { return contentType; }
    public byte[] getData() { return data; }
    public File getFile() { return file; }
    public long getSize() { return size; }
    
    public boolean isFileAttachment() {
        return file != null;
    }
}
