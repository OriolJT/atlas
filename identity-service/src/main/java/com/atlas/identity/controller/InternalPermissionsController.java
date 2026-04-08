package com.atlas.identity.controller;

import com.atlas.identity.dto.PermissionMappingResponse;
import com.atlas.identity.service.RoleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/permissions")
public class InternalPermissionsController {

    private final RoleService roleService;

    public InternalPermissionsController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    public ResponseEntity<PermissionMappingResponse> getPermissionMappings(@RequestParam UUID tenantId) {
        var response = roleService.getAllPermissionMappings(tenantId);
        return ResponseEntity.ok(response);
    }
}
