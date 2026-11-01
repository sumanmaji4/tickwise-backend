package com.tickwise.support;

import com.tickwise.shared.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] content, String contentType) {
        objects.put(key, content.clone());
    }

    @Override
    public InputStream get(String key) {
        byte[] content = objects.get(key);
        if (content == null) {
            throw new NoSuchElementException(key);
        }
        return new ByteArrayInputStream(content);
    }

    public boolean contains(String key) {
        return objects.containsKey(key);
    }
}
