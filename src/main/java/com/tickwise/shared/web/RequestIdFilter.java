package com.tickwise.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a correlation id, reusing a well-formed inbound {@code X-Request-Id}
 * (e.g. from a load balancer) and echoing it back. Runs before Spring Security so even
 * 401 responses carry the id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String inbound = request.getHeader(RequestIds.HEADER);
        // Untrusted header ends up in logs; only accept a conservative charset to prevent log injection.
        String requestId = inbound != null && SAFE_ID.matcher(inbound).matches()
                ? inbound
                : UUID.randomUUID().toString();
        MDC.put(RequestIds.REQUEST_ID, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIds.REQUEST_ID);
        }
    }
}
