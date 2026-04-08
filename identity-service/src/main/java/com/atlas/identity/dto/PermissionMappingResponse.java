package com.atlas.identity.dto;

import com.atlas.identity.domain.Permission;
import com.atlas.identity.domain.Role;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public record PermissionMappingResponse(List<RoleMappingEntry> roles) {

    public record RoleMappingEntry(String roleName, Set<String> permissions) {

        public static RoleMappingEntry from(Role role) {
            Set<String> permissionNames = role.getPermissions().stream()
                    .map(Permission::getName)
                    .collect(Collectors.toSet());
            return new RoleMappingEntry(role.getName(), permissionNames);
        }
    }

    public static PermissionMappingResponse from(List<Role> roles) {
        List<RoleMappingEntry> entries = roles.stream()
                .map(RoleMappingEntry::from)
                .toList();
        return new PermissionMappingResponse(entries);
    }
}
