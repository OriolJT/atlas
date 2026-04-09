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
class AuditEventControllerIntegrationTest {

    private static final String TEST_JWT_SECRET =
            "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtYXQtbGVhc3QtMjU2LWJpdHMtbG9uZy1mb3ItaHMyNTY=";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuditEventRepository auditEventRepository;

    private UUID tenantId;
    private String authToken;

    @BeforeEach
    void setUp() {
        auditEventRepository.deleteAll();
        tenantId = UUID.randomUUID();
        authToken = generateTestToken(tenantId);
    }

    @Test
    void queryWithNoFilters_returnsAllSeededEventsForTenant() {
        AuditEvent event1 = buildEvent(tenantId, "USER_LOGIN", "USER");
        AuditEvent event2 = buildEvent(tenantId, "ORDER_CREATED", "ORDER");
        auditEventRepository.saveAll(List.of(event1, event2));

        ResponseEntity<List<AuditEventResponse>> response = restTemplate.exchange(
                "/api/v1/audit-events",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<AuditEventResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).hasSize(2);
        assertThat(body).extracting(AuditEventResponse::tenantId)
                .containsOnly(tenantId);
    }

    @Test
    void queryWithEventTypeFilter_returnsOnlyMatchingEvents() {
        AuditEvent loginEvent = buildEvent(tenantId, "USER_LOGIN", "USER");
        AuditEvent orderEvent = buildEvent(tenantId, "ORDER_CREATED", "ORDER");
        auditEventRepository.saveAll(List.of(loginEvent, orderEvent));

        ResponseEntity<List<AuditEventResponse>> response = restTemplate.exchange(
                "/api/v1/audit-events?event_type=USER_LOGIN",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<AuditEventResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).eventType()).isEqualTo("USER_LOGIN");
        assertThat(body.get(0).auditEventId()).isEqualTo(loginEvent.getAuditEventId());
    }

    @Test
    void queryWithEventTypeFilter_noMatches_returnsEmptyList() {
        AuditEvent loginEvent = buildEvent(tenantId, "USER_LOGIN", "USER");
        auditEventRepository.save(loginEvent);

        ResponseEntity<List<AuditEventResponse>> response = restTemplate.exchange(
                "/api/v1/audit-events?event_type=ORDER_CREATED",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders()),
                new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<AuditEventResponse> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).isEmpty();
    }

    // ---- helpers ----

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
