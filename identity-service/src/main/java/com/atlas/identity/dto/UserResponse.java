package com.atlas.identity.dto;

import com.atlas.identity.domain.User;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID userId,
        UUID tenantId,
        String email,
        String firstName,
        String lastName,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getUserId(),
                user.getTenantId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getStatus().name(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
