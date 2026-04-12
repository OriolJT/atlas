package com.atlas.identity.controller;

import com.atlas.identity.dto.AssignPermissionsRequest;
import com.atlas.identity.dto.CreateRoleRequest;
import com.atlas.identity.dto.RoleResponse;
import com.atlas.identity.security.TenantContext;
import com.atlas.identity.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Roles", description = "Role definition and permission assignment")
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleService roleService;
    private final TenantContext tenantContext;

    public RoleController(RoleService roleService, TenantContext tenantContext) {
        this.roleService = roleService;
        this.tenantContext = tenantContext;
    }

    @Operation(summary = "Create role", description = "Define a new role within the current tenant")
    @ApiResponse(responseCode = "201", description = "Role created")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "409", description = "Role name already exists in this tenant")
    @PostMapping
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        // Derive tenantId from authenticated context to prevent cross-tenant creation
        var securedRequest = new CreateRoleRequest(
                tenantContext.getTenantId(),
                request.name(),
                request.description());
        var role = roleService.createRole(securedRequest);
        var response = RoleResponse.from(role);
        var location = URI.create("/api/v1/roles/" + role.getRoleId());
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "Assign permissions", description = "Replace the permission set for an existing role")
    @ApiResponse(responseCode = "200", description = "Permissions updated")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "404", description = "Role not found")
    @PostMapping("/{id}/permissions")
    public ResponseEntity<RoleResponse> assignPermissions(
            @PathVariable UUID id,
            @Valid @RequestBody AssignPermissionsRequest request) {
        var role = roleService.assignPermissions(id, tenantContext.getTenantId(), request);
        var response = RoleResponse.from(role);
        return ResponseEntity.ok(response);
    }
}
