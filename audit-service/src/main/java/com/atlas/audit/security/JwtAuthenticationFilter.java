package com.atlas.audit.security;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.common.security.InvalidTokenException;
import com.atlas.common.security.JwtTokenParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenParser jwtTokenParser;
    private final TenantContext tenantContext;

    public JwtAuthenticationFilter(JwtTokenParser jwtTokenParser, TenantContext tenantContext) {
        this.jwtTokenParser = jwtTokenParser;
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());

            try {
                AuthenticatedPrincipal principal = jwtTokenParser.parse(token);

                var authorities = principal.roles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();

                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, authorities);

                SecurityContextHolder.getContext().setAuthentication(authentication);
                tenantContext.setTenantId(principal.tenantId());
            } catch (InvalidTokenException e) {
                // Invalid token — clear context and let Spring Security return 401
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
