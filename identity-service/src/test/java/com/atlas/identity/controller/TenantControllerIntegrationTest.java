package com.atlas.identity.controller;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.dto.TenantResponse;
import com.atlas.identity.security.JwtTokenProvider;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TenantControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String authToken;

    @BeforeEach
    void setUp() {
        authToken = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), UUID.randomUUID(), List.of("TENANT_ADMIN"));
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    @Test
    void createTenant_returnsCreatedWithLocation() {
        var request = new CreateTenantRequest("Test Corp", "test-corp");

        ResponseEntity<TenantResponse> response = restTemplate.postForEntity(
                "/api/v1/tenants", request, TenantResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath()).contains("/api/v1/tenants/");

        TenantResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.tenantId()).isNotNull();
        assertThat(body.name()).isEqualTo("Test Corp");
        assertThat(body.slug()).isEqualTo("test-corp");
        assertThat(body.status()).isEqualTo("ACTIVE");
        assertThat(body.createdAt()).isNotNull();
        assertThat(body.updatedAt()).isNotNull();
    }

    @Test
    void getTenant_existingId_returnsTenant() {
        var request = new CreateTenantRequest("Beta Inc", "beta-inc");
        ResponseEntity<TenantResponse> createResponse = restTemplate.postForEntity(
                "/api/v1/tenants", request, TenantResponse.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId = createResponse.getBody().tenantId();

        ResponseEntity<TenantResponse> getResponse = restTemplate.exchange(
                "/api/v1/tenants/" + tenantId, HttpMethod.GET,
                new HttpEntity<>(authHeaders()), TenantResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        TenantResponse body = getResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.tenantId()).isEqualTo(tenantId);
        assertThat(body.name()).isEqualTo("Beta Inc");
        assertThat(body.slug()).isEqualTo("beta-inc");
    }

    @Test
    void getTenant_nonExistingId_returns404() {
        UUID randomId = UUID.randomUUID();

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/tenants/" + randomId, HttpMethod.GET,
                new HttpEntity<>(authHeaders()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createTenant_duplicateSlug_returns400() {
        var request = new CreateTenantRequest("Gamma LLC", "gamma-llc");
        restTemplate.postForEntity("/api/v1/tenants", request, TenantResponse.class);

        var duplicateRequest = new CreateTenantRequest("Gamma Other", "gamma-llc");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/tenants", duplicateRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("already exists");
    }

    @Test
    void createTenant_invalidSlug_returns400() {
        var request = new CreateTenantRequest("Delta Corp", "INVALID SLUG!");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/tenants", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createTenant_blankName_returns400() {
        var request = new CreateTenantRequest("", "valid-slug");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/tenants", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
