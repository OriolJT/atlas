package com.atlas.identity.controller;

import com.atlas.identity.dto.CreateUserRequest;
import com.atlas.identity.dto.UserResponse;
import com.atlas.identity.security.TenantContext;
import com.atlas.identity.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Users", description = "User creation and lookup within a tenant")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final TenantContext tenantContext;

    public UserController(UserService userService, TenantContext tenantContext) {
        this.userService = userService;
        this.tenantContext = tenantContext;
    }

    @Operation(summary = "Create user", description = "Create a new user within the current tenant")
    @ApiResponse(responseCode = "201", description = "User created")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "409", description = "Email already registered in this tenant")
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        // Derive tenantId from authenticated context to prevent cross-tenant creation
        var securedRequest = new CreateUserRequest(
                tenantContext.getTenantId(),
                request.email(),
                request.password(),
                request.firstName(),
                request.lastName());
        var user = userService.createUser(securedRequest);
        var response = UserResponse.from(user);
        var location = URI.create("/api/v1/users/" + user.getUserId());
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "Get user", description = "Retrieve a user by UUID, scoped to the caller's tenant when a tenant context is present")
    @ApiResponse(responseCode = "200", description = "User found")
    @ApiResponse(responseCode = "404", description = "User not found")
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable UUID id) {
        var result = tenantContext.isSet()
                ? userService.findByTenantIdAndUserId(tenantContext.getTenantId(), id)
                : userService.findById(id);
        return result
                .map(UserResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
