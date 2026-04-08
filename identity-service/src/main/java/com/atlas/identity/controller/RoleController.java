package com.atlas.identity.controller;

import com.atlas.identity.dto.AssignPermissionsRequest;
import com.atlas.identity.dto.CreateRoleRequest;
import com.atlas.identity.dto.RoleResponse;
import com.atlas.identity.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @PostMapping
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        var role = roleService.createRole(request);
        var response = RoleResponse.from(role);
        var location = URI.create("/api/v1/roles/" + role.getRoleId());
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/{id}/permissions")
    public ResponseEntity<RoleResponse> assignPermissions(
            @PathVariable UUID id,
            @Valid @RequestBody AssignPermissionsRequest request) {
        var role = roleService.assignPermissions(id, request);
        var response = RoleResponse.from(role);
        return ResponseEntity.ok(response);
    }
}
