package com.atlas.identity.dto;

import com.atlas.identity.domain.Permission;
import com.atlas.identity.domain.Role;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public record RoleResponse(
        UUID roleId,
        UUID tenantId,
        String name,
        String description,
        Set<String> permissions,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static RoleResponse from(Role role) {
        Set<String> permissionNames = role.getPermissions().stream()
                .map(Permission::getName)
                .collect(Collectors.toSet());
        return new RoleResponse(
                role.getRoleId(),
                role.getTenantId(),
                role.getName(),
                role.getDescription(),
                permissionNames,
                role.getCreatedAt(),
                role.getUpdatedAt());
    }
}
