package com.atlas.identity.controller;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.dto.CreateUserRequest;
import com.atlas.identity.dto.TenantResponse;
import com.atlas.identity.dto.UserResponse;
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
class UserControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private UUID tenantId;
    private String authToken;

    @BeforeEach
    void setUp() {
        var tenantRequest = new CreateTenantRequest("User Test Tenant " + UUID.randomUUID(), "user-test-" + UUID.randomUUID());
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

    @Test
    void createUser_returnsCreatedWithLocation() {
        var request = new CreateUserRequest(tenantId, "alice@example.com", "SecurePass1", "Alice", "Smith");

        ResponseEntity<UserResponse> response = restTemplate.exchange(
                "/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()), UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath()).contains("/api/v1/users/");

        UserResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.userId()).isNotNull();
        assertThat(body.tenantId()).isEqualTo(tenantId);
        assertThat(body.email()).isEqualTo("alice@example.com");
        assertThat(body.firstName()).isEqualTo("Alice");
        assertThat(body.lastName()).isEqualTo("Smith");
        assertThat(body.status()).isEqualTo("ACTIVE");
        assertThat(body.createdAt()).isNotNull();
        assertThat(body.updatedAt()).isNotNull();
    }

    @Test
    void createUser_passwordNotInResponse() {
        var request = new CreateUserRequest(tenantId, "bob@example.com", "SecurePass1", "Bob", "Jones");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).doesNotContain("SecurePass1");
        assertThat(response.getBody()).doesNotContain("passwordHash");
        assertThat(response.getBody()).doesNotContain("password");
    }

    @Test
    void getUser_existingId_returnsUser() {
        var request = new CreateUserRequest(tenantId, "carol@example.com", "SecurePass1", "Carol", "White");
        ResponseEntity<UserResponse> createResponse = restTemplate.exchange(
                "/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()), UserResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID userId = createResponse.getBody().userId();

        ResponseEntity<UserResponse> getResponse = restTemplate.exchange(
                "/api/v1/users/" + userId, HttpMethod.GET,
                new HttpEntity<>(authHeaders()), UserResponse.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        UserResponse body = getResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.userId()).isEqualTo(userId);
        assertThat(body.email()).isEqualTo("carol@example.com");
    }

    @Test
    void getUser_nonExistingId_returns404() {
        UUID randomId = UUID.randomUUID();

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/" + randomId, HttpMethod.GET,
                new HttpEntity<>(authHeaders()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createUser_duplicateEmail_returns400() {
        var request = new CreateUserRequest(tenantId, "duplicate@example.com", "SecurePass1", "Dave", "Brown");
        restTemplate.exchange("/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()), UserResponse.class);

        var duplicateRequest = new CreateUserRequest(tenantId, "duplicate@example.com", "AnotherPass1", "Dave", "Brown");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(duplicateRequest, authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("already exists");
    }

    @Test
    void createUser_nonExistentTenant_returns400() {
        var request = new CreateUserRequest(UUID.randomUUID(), "eve@example.com", "SecurePass1", "Eve", "Green");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("does not exist");
    }

    @Test
    void createUser_invalidEmail_returns400() {
        var request = new CreateUserRequest(tenantId, "not-an-email", "SecurePass1", "Frank", "Black");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createUser_shortPassword_returns400() {
        var request = new CreateUserRequest(tenantId, "grace@example.com", "short", "Grace", "Blue");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users", HttpMethod.POST,
                new HttpEntity<>(request, authHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
