package com.tickwise.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Metadata only; the bytes live in object storage under {@link #getStorageKey()}. */
@Entity
@Table(name = "attachments")
class Attachment {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "message_id", nullable = false, updatable = false)
    private UUID messageId;

    @Column(nullable = false, updatable = false)
    private String filename;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Attachment() {}

    Attachment(UUID id, UUID tenantId, UUID messageId, String filename, String contentType, long sizeBytes,
               String storageKey, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.messageId = messageId;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.createdAt = createdAt;
    }

    UUID getId() { return id; }
    UUID getMessageId() { return messageId; }
    String getFilename() { return filename; }
    String getContentType() { return contentType; }
    long getSizeBytes() { return sizeBytes; }
    String getStorageKey() { return storageKey; }

    AttachmentView toView() {
        return new AttachmentView(id, filename, contentType, sizeBytes);
    }
}
