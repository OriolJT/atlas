package com.atlas.identity.controller;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.dto.ApiKeyResponse;
import com.atlas.identity.dto.CreateApiKeyRequest;
import com.atlas.identity.dto.CreateServiceAccountRequest;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.dto.ServiceAccountResponse;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ServiceAccountControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private UUID tenantId;
    private String authToken;

    @BeforeEach
    void setUp() {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        var tenantRequest = new CreateTenantRequest("SA Test Tenant " + uniqueId, "sa-test-" + uniqueId);
        ResponseEntity<TenantResponse> tenantResponse = restTemplate.postForEntity(
                "/api/v1/tenants", tenantRequest, TenantResponse.class);
        assertThat(tenantResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        tenantId = tenantResponse.getBody().tenantId();
        authToken = jwtTokenProvider.generateAccessToken(
                UUID.randomUUID(), tenantId, List.of("TENANT_ADMIN"));
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(authToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // Test 1: Create service account and generate API key → 201
    @Test
    void createServiceAccount_andGenerateApiKey_returns201() {
        // Create service account
        String saName = "my-service-" + UUID.randomUUID();
        var saRequest = new CreateServiceAccountRequest(tenantId, saName);

        ResponseEntity<ServiceAccountResponse> saResponse = restTemplate.exchange(
                "/api/v1/service-accounts", HttpMethod.POST,
                new HttpEntity<>(saRequest, authHeaders()), ServiceAccountResponse.class);

        assertThat(saResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(saResponse.getHeaders().getLocation()).isNotNull();
        ServiceAccountResponse sa = saResponse.getBody();
        assertThat(sa).isNotNull();
        assertThat(sa.serviceAccountId()).isNotNull();
        assertThat(sa.tenantId()).isEqualTo(tenantId);
        assertThat(sa.name()).isEqualTo(saName);
        assertThat(sa.status()).isEqualTo("ACTIVE");

        // Generate API key for the service account
        var keyRequest = new CreateApiKeyRequest(sa.serviceAccountId(), null);

        ResponseEntity<ApiKeyResponse> keyResponse = restTemplate.exchange(
                "/api/v1/api-keys", HttpMethod.POST,
                new HttpEntity<>(keyRequest, authHeaders()), ApiKeyResponse.class);

        assertThat(keyResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(keyResponse.getHeaders().getLocation()).isNotNull();
        ApiKeyResponse key = keyResponse.getBody();
        assertThat(key).isNotNull();
        assertThat(key.apiKeyId()).isNotNull();
        assertThat(key.serviceAccountId()).isEqualTo(sa.serviceAccountId());
        assertThat(key.tenantId()).isEqualTo(tenantId);
        assertThat(key.rawKey()).isNotNull();
        assertThat(key.rawKey()).startsWith("atl_");
        assertThat(key.status()).isEqualTo("ACTIVE");
    }

    // Test 2: Authenticate with API key (X-API-Key header) → 200 on a protected endpoint
    @Test
    void authenticateWithApiKey_onProtectedEndpoint_returns200() {
        // Create service account and generate API key
        String saName = "auth-sa-" + UUID.randomUUID();
        ServiceAccountResponse sa = restTemplate.exchange(
                "/api/v1/service-accounts", HttpMethod.POST,
                new HttpEntity<>(new CreateServiceAccountRequest(tenantId, saName), authHeaders()),
                ServiceAccountResponse.class).getBody();
        assertThat(sa).isNotNull();

        ApiKeyResponse key = restTemplate.exchange(
                "/api/v1/api-keys", HttpMethod.POST,
                new HttpEntity<>(new CreateApiKeyRequest(sa.serviceAccountId(), null), authHeaders()),
                ApiKeyResponse.class).getBody();
        assertThat(key).isNotNull();
        String rawKey = key.rawKey();

        // Use API key to access a protected endpoint
        HttpHeaders apiKeyHeaders = new HttpHeaders();
        apiKeyHeaders.set("X-API-Key", rawKey);
        apiKeyHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Create another service account using X-API-Key auth (protected endpoint)
        String anotherSaName = "another-sa-" + UUID.randomUUID();
        ResponseEntity<ServiceAccountResponse> response = restTemplate.exchange(
                "/api/v1/service-accounts", HttpMethod.POST,
                new HttpEntity<>(new CreateServiceAccountRequest(tenantId, anotherSaName), apiKeyHeaders),
                ServiceAccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo(anotherSaName);
    }

    // Test 3: Authenticate with invalid API key → 401
    @Test
    void authenticateWithInvalidApiKey_returns401() {
        HttpHeaders apiKeyHeaders = new HttpHeaders();
        apiKeyHeaders.set("X-API-Key", "atl_invalid_key_that_does_not_exist");
        apiKeyHeaders.setContentType(MediaType.APPLICATION_JSON);

        String saName = "should-not-create-" + UUID.randomUUID();
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/service-accounts", HttpMethod.POST,
                new HttpEntity<>(new CreateServiceAccountRequest(tenantId, saName), apiKeyHeaders),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
