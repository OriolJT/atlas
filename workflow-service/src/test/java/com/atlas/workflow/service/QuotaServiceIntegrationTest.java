package com.atlas.workflow.service;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.dto.CreateDefinitionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for per-tenant quota enforcement.
 *
 * The test profile (application-test.yml) sets:
 *   atlas.quota.default-max-definitions=2
 *
 * Because identity-service is not running, QuotaService falls back to those
 * defaults — allowing the quota ceiling to be hit after just 2 definitions.
 *
 * Each test uses a fresh, randomly-generated tenantId so tests are isolated
 * from each other within the shared database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class QuotaServiceIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "test-secret-key-that-is-at-least-256-bits-long-for-hs256";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private QuotaService quotaService;

    private UUID tenantId;
    private String authToken;

    @BeforeEach
    void setUp() {
        // Fresh tenant per test → zero existing definitions → clean quota slate
        tenantId = UUID.randomUUID();
        authToken = generateTestToken(tenantId);
        // Evict any cached quota entry for this (new) tenant just to be safe
        quotaService.evictCache(tenantId);
    }

    @Test
    void createDefinition_quotaIsEnforced_thirdCallReturns429() {
        // First definition — within limit (default max = 2)
        ResponseEntity<String> first = createDefinition("quota-wf", 1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second definition — still within limit
        ResponseEntity<String> second = createDefinition("quota-wf", 2);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Third definition — quota exceeded → 429 with ATLAS-WF-008
        ResponseEntity<String> third = createDefinition("quota-wf", 3);
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(third.getBody()).contains("ATLAS-WF-008");
    }

    @Test
    void createDefinition_withinQuota_succeeds() {
        ResponseEntity<String> response = createDefinition("within-quota", 1);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<String> createDefinition(String name, int version) {
        var request = new CreateDefinitionRequest(
                name,
                version,
                Map.of("step1", Map.of("type", "HTTP_CALL", "url", "https://example.com")),
                null,
                "MANUAL"
        );
        return restTemplate.exchange(
                "/api/v1/workflow-definitions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                String.class
        );
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
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
}
