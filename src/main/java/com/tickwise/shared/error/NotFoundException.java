package com.tickwise.shared.error;

import org.springframework.http.HttpStatus;

/**
 * The resource does not exist in the caller's tenant. Resources owned by another tenant are
 * reported the same way, so ids from other tenants cannot be probed for existence.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Resource not found", resource + " " + id + " was not found");
    }
}
