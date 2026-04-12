package com.atlas.identity.controller;

import com.atlas.identity.dto.PermissionMappingResponse;
import com.atlas.identity.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Internal endpoint for service-to-service communication.
 * Requires a valid {@code X-Internal-Api-Key} header.
 *
 * <p>TODO: In production, these endpoints should also be network-restricted
 * (e.g., via Kubernetes NetworkPolicy or service mesh) so only internal
 * services can reach them.
 */
@Tag(name = "Internal - Permissions", description = "Internal endpoint for resolving tenant permission mappings (service-to-service only)")
@RestController
@RequestMapping("/api/v1/internal/permissions")
public class InternalPermissionsController {

    private final RoleService roleService;
    private final String internalApiKey;

    public InternalPermissionsController(RoleService roleService,
                                          @Value("${atlas.internal.api-key}") String internalApiKey) {
        this.roleService = roleService;
        this.internalApiKey = internalApiKey;
    }

    @Operation(summary = "Get permission mappings", description = "Return all role-to-permission mappings for a tenant, used by downstream services for authorization checks")
    @ApiResponse(responseCode = "200", description = "Permission mappings returned")
    @ApiResponse(responseCode = "400", description = "Missing or invalid tenantId parameter")
    @ApiResponse(responseCode = "403", description = "Invalid or missing internal API key")
    @GetMapping
    public ResponseEntity<PermissionMappingResponse> getPermissionMappings(
            @RequestParam UUID tenantId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        if (apiKey == null || !constantTimeEquals(apiKey, internalApiKey)) {
            return ResponseEntity.status(403).build();
        }
        var response = roleService.getAllPermissionMappings(tenantId);
        return ResponseEntity.ok(response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
