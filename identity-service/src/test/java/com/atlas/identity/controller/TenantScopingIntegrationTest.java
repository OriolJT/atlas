package com.atlas.identity.controller;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.dto.CreateUserRequest;
import com.atlas.identity.dto.LoginRequest;
import com.atlas.identity.dto.LoginResponse;
import com.atlas.identity.dto.TenantResponse;
import com.atlas.identity.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class TenantScopingIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void authenticatedAsTenantA_cannotReadTenantBUser() {
        // Create tenant A and a user in it
        String uniqueA = UUID.randomUUID().toString().substring(0, 8);
        var tenantARequest = new CreateTenantRequest("Tenant A " + uniqueA, "tenant-a-" + uniqueA);
        TenantResponse tenantA = restTemplate.postForEntity("/api/v1/tenants", tenantARequest, TenantResponse.class).getBody();
        assertThat(tenantA).isNotNull();

        String emailA = "user-a-" + uniqueA + "@example.com";
        var userARequest = new CreateUserRequest(tenantA.tenantId(), emailA, "SecurePass1", "User", "A");
        restTemplate.postForEntity("/api/v1/users", userARequest, UserResponse.class);

        // Create tenant B and a user in it
        String uniqueB = UUID.randomUUID().toString().substring(0, 8);
        var tenantBRequest = new CreateTenantRequest("Tenant B " + uniqueB, "tenant-b-" + uniqueB);
        TenantResponse tenantB = restTemplate.postForEntity("/api/v1/tenants", tenantBRequest, TenantResponse.class).getBody();
        assertThat(tenantB).isNotNull();

        String emailB = "user-b-" + uniqueB + "@example.com";
        var userBRequest = new CreateUserRequest(tenantB.tenantId(), emailB, "SecurePass1", "User", "B");
        UserResponse userB = restTemplate.postForEntity("/api/v1/users", userBRequest, UserResponse.class).getBody();
        assertThat(userB).isNotNull();

        // Login as tenant A's user
        var loginRequest = new LoginRequest(emailA, "SecurePass1");
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity("/api/v1/auth/login", loginRequest, LoginResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = loginResponse.getBody().accessToken();

        // Try to access tenant B's user using tenant A's token
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<UserResponse> response = restTemplate.exchange(
                "/api/v1/users/" + userB.userId(), HttpMethod.GET, entity, UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void authenticatedAsTenantA_canReadOwnUser() {
        // Create a tenant and a user
        String unique = UUID.randomUUID().toString().substring(0, 8);
        var tenantRequest = new CreateTenantRequest("Tenant Own " + unique, "tenant-own-" + unique);
        TenantResponse tenant = restTemplate.postForEntity("/api/v1/tenants", tenantRequest, TenantResponse.class).getBody();
        assertThat(tenant).isNotNull();

        String email = "user-own-" + unique + "@example.com";
        var userRequest = new CreateUserRequest(tenant.tenantId(), email, "SecurePass1", "Own", "User");
        UserResponse createdUser = restTemplate.postForEntity("/api/v1/users", userRequest, UserResponse.class).getBody();
        assertThat(createdUser).isNotNull();

        // Login as that user
        var loginRequest = new LoginRequest(email, "SecurePass1");
        ResponseEntity<LoginResponse> loginResponse = restTemplate.postForEntity("/api/v1/auth/login", loginRequest, LoginResponse.class);
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = loginResponse.getBody().accessToken();

        // Access own user profile
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<UserResponse> response = restTemplate.exchange(
                "/api/v1/users/" + createdUser.userId(), HttpMethod.GET, entity, UserResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().userId()).isEqualTo(createdUser.userId());
        assertThat(response.getBody().email()).isEqualTo(email);
    }
}
