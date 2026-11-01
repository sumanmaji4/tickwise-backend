package com.tickwise.ticket;

import java.util.UUID;

/** An attachment whose bytes are already in object storage under {@code storageKey}. */
public record NewAttachment(UUID id, String filename, String contentType, long sizeBytes, String storageKey) {}
