package com.tickwise.auth;

import com.tickwise.shared.security.AuthenticatedUser;
import com.tickwise.shared.web.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds tenant and user ids to the logging context once the bearer token is verified.
 * Registered inside the security chain only; deliberately not a {@code @Component}, which would
 * also register it as a servlet filter that runs before authentication.
 */
class TenantMdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            MDC.put(RequestIds.TENANT_ID, user.tenantId().toString());
            MDC.put(RequestIds.USER_ID, user.userId().toString());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(RequestIds.TENANT_ID);
            MDC.remove(RequestIds.USER_ID);
        }
    }
}
