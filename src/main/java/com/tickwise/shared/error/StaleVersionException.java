package com.tickwise.shared.error;

import org.springframework.http.HttpStatus;

/** The client edited a resource based on an outdated version (optimistic concurrency, HTTP 409). */
public class StaleVersionException extends ApiException {

    public StaleVersionException(String resource, Object id, long expected, long actual) {
        super(HttpStatus.CONFLICT, ProblemTypes.CONCURRENT_MODIFICATION, "Concurrent modification",
                resource + " " + id + " is at version " + actual + ", not " + expected + "; reload and retry");
    }
}
