package com.atlas.identity.controller;

import com.atlas.identity.dto.CreateTenantRequest;
import com.atlas.identity.dto.TenantResponse;
import com.atlas.identity.service.TenantService;
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

@Tag(name = "Tenants", description = "Tenant provisioning and lookup")
@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Operation(summary = "Create tenant", description = "Provision a new tenant with an isolated namespace")
    @ApiResponse(responseCode = "201", description = "Tenant created")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "409", description = "Tenant slug already exists")
    @PostMapping
    public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        var tenant = tenantService.createTenant(request);
        var response = TenantResponse.from(tenant);
        var location = URI.create("/api/v1/tenants/" + tenant.getTenantId());
        return ResponseEntity.created(location).body(response);
    }

    @Operation(summary = "Get tenant", description = "Retrieve a tenant by its UUID")
    @ApiResponse(responseCode = "200", description = "Tenant found")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> getTenant(@PathVariable UUID id) {
        return tenantService.findById(id)
                .map(TenantResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
