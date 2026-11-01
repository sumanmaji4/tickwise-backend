package com.tickwise.shared.storage;

import java.io.InputStream;

/** Blob storage for attachments. The database keeps only metadata and the object key. */
public interface ObjectStorage {

    void put(String key, byte[] content, String contentType);

    /** Caller closes the stream. */
    InputStream get(String key);
}
