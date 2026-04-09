package com.atlas.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class JwtTokenParser {

    private final SecretKey signingKey;

    public JwtTokenParser(String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public AuthenticatedPrincipal parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Token must not be null or blank");
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException("Token has expired", e);
        } catch (JwtException e) {
            throw new InvalidTokenException("Token is invalid: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Token is malformed: " + e.getMessage(), e);
        }

        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidTokenException("Token missing subject claim");
        }

        String tenantIdStr = claims.get("tenant_id", String.class);
        if (tenantIdStr == null || tenantIdStr.isBlank()) {
            throw new InvalidTokenException("Token missing tenant_id claim");
        }

        UUID userId;
        UUID tenantId;
        try {
            userId = UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Token subject is not a valid UUID: " + subject, e);
        }
        try {
            tenantId = UUID.fromString(tenantIdStr);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("Token tenant_id is not a valid UUID: " + tenantIdStr, e);
        }

        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        if (roles == null) {
            roles = List.of();
        }

        return new AuthenticatedPrincipal(userId, tenantId, roles);
    }
}
