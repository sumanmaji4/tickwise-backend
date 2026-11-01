package com.tickwise.shared.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(String detail) {
        super(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Conflict", detail);
    }
}
