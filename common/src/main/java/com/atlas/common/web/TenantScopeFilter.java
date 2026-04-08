package com.atlas.common.web;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.common.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class TenantScopeFilter extends OncePerRequestFilter {

    public static final String ATTRIBUTE_NAME = "authenticatedPrincipal";

    private final TenantContext tenantContext;

    public TenantScopeFilter(TenantContext tenantContext) {
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Object attribute = request.getAttribute(ATTRIBUTE_NAME);
        if (attribute instanceof AuthenticatedPrincipal principal) {
            tenantContext.setPrincipal(principal);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            tenantContext.clear();
        }
    }
}
