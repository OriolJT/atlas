package com.atlas.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenParserTest {

    // Raw UTF-8 secret (must be at least 32 bytes for HMAC-SHA256)
    private static final String SECRET = "test-secret-key-that-is-long-enough-32b";

    private static final String WRONG_SECRET = "wrong-secret-key-that-is-long-enough-32";

    private JwtTokenParser parser;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        parser = new JwtTokenParser(SECRET);
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private String buildToken(UUID userId, UUID tenantId, List<String> roles, long expiryMs) {
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("tenant_id", tenantId.toString())
                .expiration(new Date(System.currentTimeMillis() + expiryMs))
                .signWith(signingKey);
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return builder.compact();
    }

    @Test
    void parseValidToken() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        List<String> roles = List.of("admin", "user");

        String token = buildToken(userId, tenantId, roles, 60_000);
        AuthenticatedPrincipal principal = parser.parse(token);

        assertEquals(userId, principal.userId());
        assertEquals(tenantId, principal.tenantId());
        assertEquals(roles, principal.roles());
    }

    @Test
    void parseValidTokenWithEmptyRolesDefaultsToEmptyList() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        // No roles claim in token
        String token = buildToken(userId, tenantId, null, 60_000);
        AuthenticatedPrincipal principal = parser.parse(token);

        assertNotNull(principal.roles());
        assertTrue(principal.roles().isEmpty());
    }

    @Test
    void rejectNullToken() {
        InvalidTokenException ex = assertThrows(InvalidTokenException.class,
                () -> parser.parse(null));
        assertTrue(ex.getMessage().contains("blank") || ex.getMessage().contains("null"));
    }

    @Test
    void rejectBlankToken() {
        InvalidTokenException ex = assertThrows(InvalidTokenException.class,
                () -> parser.parse("   "));
        assertTrue(ex.getMessage().contains("blank") || ex.getMessage().contains("null"));
    }

    @Test
    void rejectEmptyToken() {
        assertThrows(InvalidTokenException.class, () -> parser.parse(""));
    }

    @Test
    void rejectExpiredToken() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token = buildToken(userId, tenantId, List.of(), -1000); // already expired
        assertThrows(InvalidTokenException.class, () -> parser.parse(token));
    }

    @Test
    void rejectTokenSignedWithWrongKey() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(WRONG_SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(wrongKey)
                .compact();

        assertThrows(InvalidTokenException.class, () -> parser.parse(token));
    }

    @Test
    void rejectMalformedToken() {
        assertThrows(InvalidTokenException.class, () -> parser.parse("not.a.jwt"));
    }

    @Test
    void rejectTokenMissingSubject() {
        // Build a token without a subject
        String token = Jwts.builder()
                .claim("tenant_id", UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(signingKey)
                .compact();

        assertThrows(InvalidTokenException.class, () -> parser.parse(token));
    }

    @Test
    void rejectTokenMissingTenantId() {
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(signingKey)
                .compact();

        assertThrows(InvalidTokenException.class, () -> parser.parse(token));
    }
}
