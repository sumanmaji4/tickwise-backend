package com.tickwise.ticket;

import java.util.UUID;

public record AttachmentView(UUID id, String filename, String contentType, long sizeBytes) {}
