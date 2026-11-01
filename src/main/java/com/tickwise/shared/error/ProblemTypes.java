package com.tickwise.shared.error;

import java.net.URI;

/** Stable, machine-readable {@code type} values for RFC 7807 problem responses. */
public final class ProblemTypes {

    public static final URI VALIDATION = of("validation-error");
    public static final URI NOT_FOUND = of("not-found");
    public static final URI CONFLICT = of("conflict");
    public static final URI CONCURRENT_MODIFICATION = of("concurrent-modification");
    public static final URI BAD_REQUEST = of("bad-request");
    public static final URI UNAUTHORIZED = of("unauthorized");
    public static final URI INVALID_CREDENTIALS = of("invalid-credentials");
    public static final URI FORBIDDEN = of("forbidden");
    public static final URI INTERNAL = of("internal-error");

    private ProblemTypes() {}

    private static URI of(String slug) {
        return URI.create("urn:tickwise:problem:" + slug);
    }
}
