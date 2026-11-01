package com.tickwise.shared.error;

import java.net.URI;
import java.time.Duration;
import org.springframework.http.HttpStatus;

/** Rate limit exceeded; the handler adds a {@code Retry-After} header. */
public class TooManyRequestsException extends ApiException {

    public static final URI TYPE = URI.create("urn:tickwise:problem:rate-limited");

    private final Duration retryAfter;

    public TooManyRequestsException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, TYPE, "Too many requests", "Rate limit exceeded; retry later");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
