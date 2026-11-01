package com.tickwise.shared.web;

import org.slf4j.MDC;

/** MDC keys shared by logging and error responses. */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String REQUEST_ID = "requestId";
    public static final String TENANT_ID = "tenantId";
    public static final String USER_ID = "userId";

    private RequestIds() {}

    public static String current() {
        return MDC.get(REQUEST_ID);
    }
}
