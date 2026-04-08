package com.atlas.identity.controller;

import com.atlas.identity.TestcontainersConfiguration;
import com.atlas.identity.dto.AssignPermissionsRequest;
import com.atlas.identity.dto.CreateRoleRequest;
import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.dto.PermissionMappingResponse;
import com.atlas.identity.dto.RoleResponse;
import com.atlas.identity.dto.TenantResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class InternalPermissionsControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void getPermissionMappings_withRolesAndPermissions_returnsMappings() {
        // Create a tenant
        var tenantRequest = new CreateTenantRequest("Perm Test Tenant " + UUID.randomUUID(), "perm-test-" + UUID.randomUUID());
        ResponseEntity<TenantResponse> tenantResponse = restTemplate.postForEntity(
                "/api/v1/tenants", tenantRequest, TenantResponse.class);
        assertThat(tenantResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId = tenantResponse.getBody().tenantId();

        // Create a role and assign permissions
        var roleRequest = new CreateRoleRequest(tenantId, "operator", "Operator role");
        ResponseEntity<RoleResponse> roleResponse = restTemplate.postForEntity(
                "/api/v1/roles", roleRequest, RoleResponse.class);
        assertThat(roleResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID roleId = roleResponse.getBody().roleId();

        var assignRequest = new AssignPermissionsRequest(Set.of("workflow.read", "workflow.execute"));
        restTemplate.postForEntity("/api/v1/roles/" + roleId + "/permissions", assignRequest, RoleResponse.class);

        // Query internal permissions endpoint
        ResponseEntity<PermissionMappingResponse> response = restTemplate.getForEntity(
                "/api/v1/internal/permissions?tenantId=" + tenantId, PermissionMappingResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PermissionMappingResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.roles()).hasSize(1);
        assertThat(body.roles().get(0).roleName()).isEqualTo("operator");
        assertThat(body.roles().get(0).permissions()).containsExactlyInAnyOrder("workflow.read", "workflow.execute");
    }

    @Test
    void getPermissionMappings_emptyTenant_returnsEmptyRoles() {
        // Create a tenant with no roles
        var tenantRequest = new CreateTenantRequest("Empty Tenant " + UUID.randomUUID(), "empty-test-" + UUID.randomUUID());
        ResponseEntity<TenantResponse> tenantResponse = restTemplate.postForEntity(
                "/api/v1/tenants", tenantRequest, TenantResponse.class);
        assertThat(tenantResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId = tenantResponse.getBody().tenantId();

        ResponseEntity<PermissionMappingResponse> response = restTemplate.getForEntity(
                "/api/v1/internal/permissions?tenantId=" + tenantId, PermissionMappingResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PermissionMappingResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.roles()).isEmpty();
    }
}
