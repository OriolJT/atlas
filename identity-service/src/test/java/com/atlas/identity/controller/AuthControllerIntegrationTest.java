package com.atlas.identity.controller;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.dto.CreateUserRequest;
import com.atlas.identity.dto.LoginRequest;
import com.atlas.identity.dto.LoginResponse;
import com.atlas.identity.dto.LogoutRequest;
import com.atlas.identity.dto.RefreshTokenRequest;
import com.atlas.identity.dto.TenantResponse;
import com.atlas.identity.dto.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private UUID tenantId;
    private String userEmail;
    private String userPassword;

    @BeforeEach
    void setUp() {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        var tenantRequest = new CreateTenantRequest("Auth Test Tenant " + uniqueId, "auth-test-" + uniqueId);
        ResponseEntity<TenantResponse> tenantResponse = restTemplate.postForEntity(
                "/api/v1/tenants", tenantRequest, TenantResponse.class);
        assertThat(tenantResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        tenantId = tenantResponse.getBody().tenantId();

        userEmail = "auth-user-" + uniqueId + "@example.com";
        userPassword = "SecurePass1";
        var userRequest = new CreateUserRequest(tenantId, userEmail, userPassword, "Auth", "User");
        ResponseEntity<UserResponse> userResponse = restTemplate.postForEntity(
                "/api/v1/users", userRequest, UserResponse.class);
        assertThat(userResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void login_validCredentials_returnsTokens() {
        var loginRequest = new LoginRequest(userEmail, userPassword);

        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, LoginResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        LoginResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.accessToken()).isNotBlank();
        assertThat(body.refreshToken()).isNotBlank();
        assertThat(body.userId()).isNotNull();
        assertThat(body.tenantId()).isEqualTo(tenantId);
    }

    @Test
    void login_invalidPassword_returns401() {
        var loginRequest = new LoginRequest(userEmail, "WrongPassword1");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("ATLAS-AUTH-001");
    }

    @Test
    void login_nonExistentUser_returns401() {
        var loginRequest = new LoginRequest("nobody@example.com", "SomePassword1");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_accountLockedAfter5Failures_returns423() {
        // Fail 5 times to trigger lockout
        for (int i = 0; i < 5; i++) {
            var loginRequest = new LoginRequest(userEmail, "WrongPassword1");
            restTemplate.postForEntity("/api/v1/auth/login", loginRequest, String.class);
        }

        // 6th attempt should get locked response
        var loginRequest = new LoginRequest(userEmail, userPassword);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(response.getBody()).contains("ATLAS-AUTH-002");
    }

    @Test
    void refresh_validToken_returnsNewTokens() {
        // Login first
        var loginRequest = new LoginRequest(userEmail, userPassword);
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, LoginResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refreshToken = loginResponse.getBody().refreshToken();

        // Refresh
        var refreshRequest = new RefreshTokenRequest(refreshToken);
        ResponseEntity<LoginResponse> response = restTemplate.postForEntity(
                "/api/v1/auth/refresh", refreshRequest, LoginResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        LoginResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.accessToken()).isNotBlank();
        assertThat(body.refreshToken()).isNotBlank();
        // New refresh token should be different (rotation)
        assertThat(body.refreshToken()).isNotEqualTo(refreshToken);
    }

    @Test
    void refresh_usedToken_returns401() {
        // Login first
        var loginRequest = new LoginRequest(userEmail, userPassword);
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, LoginResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refreshToken = loginResponse.getBody().refreshToken();

        // Use the refresh token once (rotates it)
        var refreshRequest = new RefreshTokenRequest(refreshToken);
        ResponseEntity<LoginResponse> firstRefresh = restTemplate.postForEntity(
                "/api/v1/auth/refresh", refreshRequest, LoginResponse.class);
        assertThat(firstRefresh.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Try to use the same refresh token again (should fail because it was revoked)
        ResponseEntity<String> secondRefresh = restTemplate.postForEntity(
                "/api/v1/auth/refresh", refreshRequest, String.class);

        assertThat(secondRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void logout_revokesToken_subsequentRefreshFails() {
        // Login first
        var loginRequest = new LoginRequest(userEmail, userPassword);
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login", loginRequest, LoginResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String refreshToken = loginResponse.getBody().refreshToken();

        // Logout
        var logoutRequest = new LogoutRequest(refreshToken);
        ResponseEntity<Void> logoutResponse = restTemplate.postForEntity(
                "/api/v1/auth/logout", logoutRequest, Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Try to refresh with the revoked token
        var refreshRequest = new RefreshTokenRequest(refreshToken);
        ResponseEntity<String> refreshResponse = restTemplate.postForEntity(
                "/api/v1/auth/refresh", refreshRequest, String.class);

        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
