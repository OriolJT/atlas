package com.atlas.identity.controller;

import com.atlas.identity.dto.TenantQuotaResponse;
import com.atlas.identity.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Internal - Tenant Quotas", description = "Internal endpoint for tenant quota lookups (service-to-service only)")
@RestController
@RequestMapping("/api/v1/internal/tenants")
public class InternalTenantController {

    private final TenantService tenantService;

    public InternalTenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @Operation(summary = "Get tenant quotas", description = "Return resource quotas configured for the given tenant")
    @ApiResponse(responseCode = "200", description = "Quotas returned")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    @GetMapping("/{tenantId}/quotas")
    public ResponseEntity<TenantQuotaResponse> getQuotas(@PathVariable UUID tenantId) {
        return tenantService.findById(tenantId)
                .map(TenantQuotaResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
