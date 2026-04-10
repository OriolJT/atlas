package com.atlas.identity.security;

import com.atlas.identity.repository.ApiKeyRepository;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyRepository apiKeyRepository;
    private final TenantContext tenantContext;

    public ApiKeyAuthenticationFilter(ApiKeyRepository apiKeyRepository, TenantContext tenantContext) {
        this.apiKeyRepository = apiKeyRepository;
        this.tenantContext = tenantContext;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String keyHash = hashKey(apiKey);
        var found = apiKeyRepository.findByKeyHash(keyHash);

        if (found.isEmpty() || !found.get().isActive()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        var key = found.get();
        // TODO: Usage tracking should be done asynchronously (e.g., via a scheduled batch
        // update or event) to avoid a synchronous DB write on every authenticated request.

        var authorities = List.of(new SimpleGrantedAuthority("ROLE_SERVICE_ACCOUNT"));
        var authentication = new UsernamePasswordAuthenticationToken(
                key.getServiceAccountId().toString(), null, authorities);
        authentication.setDetails(new JwtAuthenticationDetails(
                key.getServiceAccountId(), key.getTenantId(), List.of("SERVICE_ACCOUNT")));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        tenantContext.setTenantId(key.getTenantId());

        filterChain.doFilter(request, response);
    }

    private String hashKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
