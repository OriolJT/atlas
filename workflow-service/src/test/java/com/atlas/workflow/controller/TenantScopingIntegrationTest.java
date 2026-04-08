package com.atlas.workflow.controller;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.dto.CreateDefinitionRequest;
import com.atlas.workflow.dto.DefinitionResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
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
            "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtYXQtbGVhc3QtMjU2LWJpdHMtbG9uZy1mb3ItaHMyNTY=";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void authenticatedAsTenantA_cannotReadTenantBDefinition() {
        UUID tenantAId = UUID.randomUUID();
        UUID tenantBId = UUID.randomUUID();

        // Create a definition as tenant B
        String tokenB = generateTestToken(tenantBId);
        var request = new CreateDefinitionRequest(
                "tenant-b-workflow",
                1,
                Map.of("step1", Map.of("type", "HTTP_CALL", "url", "https://example.com")),
                null,
                "MANUAL"
        );

        ResponseEntity<DefinitionResponse> createResponse = restTemplate.exchange(
                "/api/v1/workflow-definitions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(tokenB)),
                DefinitionResponse.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantBDefinitionId = createResponse.getBody().definitionId();

        // Authenticate as tenant A and try to access tenant B's definition
        String tokenA = generateTestToken(tenantAId);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/workflow-definitions/" + tenantBDefinitionId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(tokenA)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private String generateTestToken(UUID tenantId) {
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_JWT_SECRET));
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
