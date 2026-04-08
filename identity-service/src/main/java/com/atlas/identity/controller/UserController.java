package com.atlas.identity.controller;

import com.atlas.identity.dto.CreateUserRequest;
import com.atlas.identity.dto.UserResponse;
import com.atlas.identity.security.TenantContext;
import com.atlas.identity.service.UserService;
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

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final TenantContext tenantContext;

    public UserController(UserService userService, TenantContext tenantContext) {
        this.userService = userService;
        this.tenantContext = tenantContext;
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request) {
        var user = userService.createUser(request);
        var response = UserResponse.from(user);
        var location = URI.create("/api/v1/users/" + user.getUserId());
        return ResponseEntity.created(location).body(response);
    }

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
