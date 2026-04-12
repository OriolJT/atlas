package com.atlas.identity.service;

import com.atlas.common.event.EventTypes;
import com.atlas.identity.domain.OutboxEvent;
import com.atlas.identity.domain.Permission;
import com.atlas.identity.domain.Role;
import com.atlas.identity.domain.User;
import com.atlas.identity.dto.AssignPermissionsRequest;
import com.atlas.identity.dto.CreateRoleRequest;
import com.atlas.identity.dto.PermissionMappingResponse;
import com.atlas.identity.repository.OutboxRepository;
import com.atlas.identity.repository.PermissionRepository;
import com.atlas.identity.repository.RoleRepository;
import com.atlas.identity.repository.TenantRepository;
import com.atlas.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final OutboxRepository outboxRepository;

    public RoleService(RoleRepository roleRepository, PermissionRepository permissionRepository,
                       TenantRepository tenantRepository, UserRepository userRepository,
                       OutboxRepository outboxRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Role createRole(CreateRoleRequest request) {
        if (!tenantRepository.existsById(request.tenantId())) {
            throw new IllegalArgumentException("Tenant with id '" + request.tenantId() + "' does not exist");
        }
        if (roleRepository.existsByTenantIdAndName(request.tenantId(), request.name())) {
            throw new IllegalArgumentException(
                    "Role with name '" + request.name() + "' already exists in this tenant");
        }
        var role = new Role(request.tenantId(), request.name(), request.description());
        return roleRepository.save(role);
    }

    @Transactional
    public Role assignPermissions(UUID roleId, UUID callerTenantId, AssignPermissionsRequest request) {
        var role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Role with id '" + roleId + "' does not exist"));

        if (!role.getTenantId().equals(callerTenantId)) {
            throw new IllegalArgumentException("Role with id '" + roleId + "' does not exist");
        }

        Set<Permission> permissions = permissionRepository.findByNameIn(request.permissions());
        if (permissions.size() != request.permissions().size()) {
            Set<String> foundNames = new java.util.HashSet<>();
            for (Permission p : permissions) {
                foundNames.add(p.getName());
            }
            Set<String> unknown = new java.util.HashSet<>(request.permissions());
            unknown.removeAll(foundNames);
            throw new IllegalArgumentException("Unknown permissions: " + unknown);
        }

        role.setPermissions(permissions);
        role = roleRepository.save(role);

        List<String> permissionNames = permissions.stream()
                .map(Permission::getName)
                .collect(Collectors.toList());

        Map<String, Object> payload = Map.of(
                "roleId", role.getRoleId().toString(),
                "tenantId", role.getTenantId().toString(),
                "roleName", role.getName(),
                "permissions", permissionNames
        );

        outboxRepository.save(new OutboxEvent(
                "Role", role.getRoleId(), "role.permissions_changed",
                EventTypes.TOPIC_DOMAIN_EVENTS, payload, role.getTenantId()));

        return role;
    }

    @Transactional
    public User assignRoleToUser(UUID userId, UUID roleId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User with id '" + userId + "' does not exist"));
        var role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Role with id '" + roleId + "' does not exist"));

        if (!user.getTenantId().equals(role.getTenantId())) {
            throw new IllegalArgumentException(
                    "Cross-tenant role assignment is not allowed: user tenant "
                            + user.getTenantId() + " != role tenant " + role.getTenantId());
        }

        user.addRole(role);
        user = userRepository.save(user);

        Map<String, Object> payload = Map.of(
                "userId", user.getUserId().toString(),
                "tenantId", user.getTenantId().toString(),
                "roleId", role.getRoleId().toString(),
                "roleName", role.getName()
        );

        outboxRepository.save(new OutboxEvent(
                "User", user.getUserId(), "user.role_assigned",
                EventTypes.TOPIC_DOMAIN_EVENTS, payload, user.getTenantId()));

        return user;
    }

    @Transactional(readOnly = true)
    public PermissionMappingResponse getAllPermissionMappings(UUID tenantId) {
        List<Role> roles = roleRepository.findByTenantIdWithPermissions(tenantId);
        return PermissionMappingResponse.from(roles);
    }
}
