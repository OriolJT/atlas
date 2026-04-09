package com.atlas.identity.controller;

import com.atlas.identity.dto.PermissionMappingResponse;
import com.atlas.identity.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Internal - Permissions", description = "Internal endpoint for resolving tenant permission mappings (service-to-service only)")
@RestController
@RequestMapping("/api/v1/internal/permissions")
public class InternalPermissionsController {

    private final RoleService roleService;

    public InternalPermissionsController(RoleService roleService) {
        this.roleService = roleService;
    }

    @Operation(summary = "Get permission mappings", description = "Return all role-to-permission mappings for a tenant, used by downstream services for authorization checks")
    @ApiResponse(responseCode = "200", description = "Permission mappings returned")
    @ApiResponse(responseCode = "400", description = "Missing or invalid tenantId parameter")
    @GetMapping
    public ResponseEntity<PermissionMappingResponse> getPermissionMappings(@RequestParam UUID tenantId) {
        var response = roleService.getAllPermissionMappings(tenantId);
        return ResponseEntity.ok(response);
    }
}
