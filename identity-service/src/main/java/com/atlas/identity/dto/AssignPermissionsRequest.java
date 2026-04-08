package com.atlas.identity.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record AssignPermissionsRequest(
        @NotEmpty(message = "Permissions must not be empty")
        Set<String> permissions) {
}
