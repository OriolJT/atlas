package com.atlas.audit.controller;

import com.atlas.audit.TestcontainersConfiguration;
import com.atlas.audit.domain.AuditEvent;
import com.atlas.audit.dto.AuditEventResponse;
import com.atlas.audit.repository.AuditEventRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TenantScopingIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "test-secret-key-that-is-at-least-256-bits-long-for-hs256";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @BeforeEach
    void setUp() {
        auditEventRepository.deleteAll();
    }

    @Test
    void authenticatedAsTenantA_onlySeesOwnAuditEvents() {
        UUID tenantAId = UUID.randomUUID();
        UUID tenantBId = UUID.randomUUID();

        // Seed one event per tenant directly via repository
        AuditEvent eventA = buildEvent(tenantAId, "USER_LOGIN", "USER");
        AuditEvent eventB = buildEvent(tenantBId, "ORDER_CREATED", "ORDER");
        auditEventRepository.saveAll(List.of(eventA, eventB));

        // Query as tenant A — should only see tenant A's event
        String tokenA = generateTestToken(tenantAId);
        ResponseEntity<List<AuditEventResponse>> response = restTemplate.exchange(
                "/api/v1/audit-events",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tokenA)),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<AuditEventResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).tenantId()).isEqualTo(tenantAId);
        assertThat(body.get(0).eventType()).isEqualTo("USER_LOGIN");
    }

    @Test
    void authenticatedAsTenantA_cannotReadTenantBEvents() {
        UUID tenantAId = UUID.randomUUID();
        UUID tenantBId = UUID.randomUUID();

        // Seed two events for tenant B, none for tenant A
        AuditEvent eventB1 = buildEvent(tenantBId, "ORDER_CREATED", "ORDER");
        AuditEvent eventB2 = buildEvent(tenantBId, "ORDER_SHIPPED", "ORDER");
        auditEventRepository.saveAll(List.of(eventB1, eventB2));

        // Query as tenant A — should see no events
        String tokenA = generateTestToken(tenantAId);
        ResponseEntity<List<AuditEventResponse>> response = restTemplate.exchange(
                "/api/v1/audit-events",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tokenA)),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<AuditEventResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).isEmpty();
    }

    @Test
    void unauthenticated_queryIsDenied() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/audit-events",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class
        );

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    // ---- helpers ----

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String generateTestToken(UUID tenantId) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("roles", List.of("TENANT_ADMIN"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    private AuditEvent buildEvent(UUID tenantId, String eventType, String resourceType) {
        return new AuditEvent(
                UUID.randomUUID(),
                tenantId,
                "USER",
                UUID.randomUUID(),
                eventType,
                resourceType,
                UUID.randomUUID(),
                Map.of("key", "value"),
                UUID.randomUUID(),
                Instant.now()
        );
    }
}
