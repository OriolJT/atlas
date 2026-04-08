package com.atlas.identity.controller;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.dto.AssignPermissionsRequest;
import com.atlas.identity.dto.CreateRoleRequest;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.dto.RoleResponse;
import com.atlas.identity.dto.TenantResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class RoleControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        var tenantRequest = new CreateTenantRequest("Role Test Tenant " + UUID.randomUUID(), "role-test-" + UUID.randomUUID());
        ResponseEntity<TenantResponse> tenantResponse = restTemplate.postForEntity(
                "/api/v1/tenants", tenantRequest, TenantResponse.class);
        assertThat(tenantResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        tenantId = tenantResponse.getBody().tenantId();
    }

    @Test
    void createRole_returnsCreated() {
        var request = new CreateRoleRequest(tenantId, "admin", "Administrator role");

        ResponseEntity<RoleResponse> response = restTemplate.postForEntity(
                "/api/v1/roles", request, RoleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath()).contains("/api/v1/roles/");

        RoleResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.roleId()).isNotNull();
        assertThat(body.tenantId()).isEqualTo(tenantId);
        assertThat(body.name()).isEqualTo("admin");
        assertThat(body.description()).isEqualTo("Administrator role");
        assertThat(body.permissions()).isEmpty();
        assertThat(body.createdAt()).isNotNull();
        assertThat(body.updatedAt()).isNotNull();
    }

    @Test
    void createRole_duplicateName_returns400() {
        var request = new CreateRoleRequest(tenantId, "duplicate-role", "First role");
        restTemplate.postForEntity("/api/v1/roles", request, RoleResponse.class);

        var duplicateRequest = new CreateRoleRequest(tenantId, "duplicate-role", "Second role");
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/roles", duplicateRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("already exists");
    }

    @Test
    void assignPermissions_returnsRoleWithPermissions() {
        var createRequest = new CreateRoleRequest(tenantId, "viewer", "Viewer role");
        ResponseEntity<RoleResponse> createResponse = restTemplate.postForEntity(
                "/api/v1/roles", createRequest, RoleResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID roleId = createResponse.getBody().roleId();

        var assignRequest = new AssignPermissionsRequest(Set.of("workflow.read", "audit.read"));
        ResponseEntity<RoleResponse> response = restTemplate.postForEntity(
                "/api/v1/roles/" + roleId + "/permissions", assignRequest, RoleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RoleResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.permissions()).containsExactlyInAnyOrder("workflow.read", "audit.read");
    }

    @Test
    void assignPermissions_unknownPermission_returns400() {
        var createRequest = new CreateRoleRequest(tenantId, "bad-role", "Role with bad permissions");
        ResponseEntity<RoleResponse> createResponse = restTemplate.postForEntity(
                "/api/v1/roles", createRequest, RoleResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID roleId = createResponse.getBody().roleId();

        var assignRequest = new AssignPermissionsRequest(Set.of("workflow.read", "nonexistent.permission"));
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/roles/" + roleId + "/permissions", assignRequest, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Unknown permissions");
    }
}
