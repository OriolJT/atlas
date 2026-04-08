package com.atlas.workflow.controller;

import com.atlas.workflow.TestcontainersConfiguration;
import com.atlas.workflow.domain.DefinitionStatus;
import com.atlas.workflow.dto.CreateDefinitionRequest;
import com.atlas.workflow.dto.DefinitionResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
class WorkflowDefinitionControllerIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtYXQtbGVhc3QtMjU2LWJpdHMtbG9uZy1mb3ItaHMyNTY=";

    @Autowired
    private TestRestTemplate restTemplate;

    private UUID tenantId;
    private String authToken;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        authToken = generateTestToken(tenantId);
    }

    @Test
    void create_returnsCreatedWithDraftStatus() {
        var request = new CreateDefinitionRequest(
                "order-processing",
                1,
                Map.of("step1", Map.of("type", "HTTP_CALL", "url", "https://example.com")),
                Map.of("step1", Map.of("type", "ROLLBACK")),
                "EVENT"
        );

        ResponseEntity<DefinitionResponse> response = restTemplate.exchange(
                "/api/v1/workflow-definitions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                DefinitionResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DefinitionResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.definitionId()).isNotNull();
        assertThat(body.tenantId()).isEqualTo(tenantId);
        assertThat(body.name()).isEqualTo("order-processing");
        assertThat(body.version()).isEqualTo(1);
        assertThat(body.status()).isEqualTo(DefinitionStatus.DRAFT);
        assertThat(body.createdAt()).isNotNull();
        assertThat(body.publishedAt()).isNull();
    }

    @Test
    void publish_transitionsDraftToPublished() {
        // Create a definition first
        var request = new CreateDefinitionRequest(
                "payment-flow",
                1,
                Map.of("step1", Map.of("type", "PAYMENT")),
                null,
                "MANUAL"
        );

        ResponseEntity<DefinitionResponse> createResponse = restTemplate.exchange(
                "/api/v1/workflow-definitions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                DefinitionResponse.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID definitionId = createResponse.getBody().definitionId();

        // Publish it
        ResponseEntity<DefinitionResponse> publishResponse = restTemplate.exchange(
                "/api/v1/workflow-definitions/" + definitionId + "/publish",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders()),
                DefinitionResponse.class
        );

        assertThat(publishResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        DefinitionResponse body = publishResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(DefinitionStatus.PUBLISHED);
        assertThat(body.publishedAt()).isNotNull();
    }

    @Test
    void publish_alreadyPublished_returns409() {
        // Create and publish
        var request = new CreateDefinitionRequest(
                "notification-flow",
                1,
                Map.of("step1", Map.of("type", "NOTIFY")),
                null,
                "EVENT"
        );

        ResponseEntity<DefinitionResponse> createResponse = restTemplate.exchange(
                "/api/v1/workflow-definitions",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()),
                DefinitionResponse.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID definitionId = createResponse.getBody().definitionId();

        // Publish first time
        restTemplate.exchange(
                "/api/v1/workflow-definitions/" + definitionId + "/publish",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders()),
                DefinitionResponse.class
        );

        // Publish second time - should fail
        ResponseEntity<String> secondPublish = restTemplate.exchange(
                "/api/v1/workflow-definitions/" + definitionId + "/publish",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders()),
                String.class
        );

        assertThat(secondPublish.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getById_unknownId_returns404() {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/workflow-definitions/" + unknownId,
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
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
