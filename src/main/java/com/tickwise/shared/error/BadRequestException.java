package com.tickwise.shared.error;

import org.springframework.http.HttpStatus;

/** A request that is well-formed but violates a domain rule. */
public class BadRequestException extends ApiException {

    public BadRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, ProblemTypes.BAD_REQUEST, "Bad request", detail);
    }
}
