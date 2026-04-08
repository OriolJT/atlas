package com.atlas.identity.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        tokenProvider = new JwtTokenProvider(
                "atlas-jwt-secret-key-that-is-at-least-256-bits-long-for-hs256",
                15, 7);
    }

    @Test
    void generateAccessToken_containsCorrectClaims() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        List<String> roles = List.of("admin", "viewer");

        String token = tokenProvider.generateAccessToken(userId, tenantId, roles);
        Claims claims = tokenProvider.parseAccessToken(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.get("tenant_id", String.class)).isEqualTo(tenantId.toString());
        assertThat(claims.get("roles", List.class)).containsExactly("admin", "viewer");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void validateAccessToken_validToken_returnsTrue() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String token = tokenProvider.generateAccessToken(userId, tenantId, List.of("admin"));

        assertThat(tokenProvider.validateAccessToken(token)).isTrue();
    }

    @Test
    void validateAccessToken_invalidToken_returnsFalse() {
        assertThat(tokenProvider.validateAccessToken("invalid.jwt.token")).isFalse();
    }

    @Test
    void validateAccessToken_expiredToken_returnsFalse() {
        // Create a provider with 0-minute expiry
        var shortLivedProvider = new JwtTokenProvider(
                "atlas-jwt-secret-key-that-is-at-least-256-bits-long-for-hs256",
                0, 7);
        String token = shortLivedProvider.generateAccessToken(UUID.randomUUID(), UUID.randomUUID(), List.of());

        assertThat(shortLivedProvider.validateAccessToken(token)).isFalse();
    }

    @Test
    void generateRefreshTokenValue_isUniqueAndLong() {
        String token1 = tokenProvider.generateRefreshTokenValue();
        String token2 = tokenProvider.generateRefreshTokenValue();

        assertThat(token1).isNotEqualTo(token2);
        // 64 bytes base64url encoded = 86 chars
        assertThat(token1.length()).isGreaterThanOrEqualTo(80);
        assertThat(token2.length()).isGreaterThanOrEqualTo(80);
    }

    @Test
    void hashRefreshToken_isDeterministic() {
        String rawToken = tokenProvider.generateRefreshTokenValue();

        String hash1 = tokenProvider.hashRefreshToken(rawToken);
        String hash2 = tokenProvider.hashRefreshToken(rawToken);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void hashRefreshToken_differentInput_differentOutput() {
        String token1 = tokenProvider.generateRefreshTokenValue();
        String token2 = tokenProvider.generateRefreshTokenValue();

        String hash1 = tokenProvider.hashRefreshToken(token1);
        String hash2 = tokenProvider.hashRefreshToken(token2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void getUserIdFromToken_returnsCorrectUserId() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String token = tokenProvider.generateAccessToken(userId, tenantId, List.of("admin"));

        UUID extractedUserId = tokenProvider.getUserIdFromToken(token);

        assertThat(extractedUserId).isEqualTo(userId);
    }

    @Test
    void getTenantIdFromToken_returnsCorrectTenantId() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String token = tokenProvider.generateAccessToken(userId, tenantId, List.of("admin"));

        UUID extractedTenantId = tokenProvider.getTenantIdFromToken(token);

        assertThat(extractedTenantId).isEqualTo(tenantId);
    }
}
