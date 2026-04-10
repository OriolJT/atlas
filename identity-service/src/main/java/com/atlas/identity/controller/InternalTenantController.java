package com.atlas.identity.controller;

import com.atlas.identity.dto.TenantQuotaResponse;
import com.atlas.identity.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal endpoint for service-to-service communication.
 * Requires a valid {@code X-Internal-Api-Key} header.
 *
 * <p>TODO: In production, these endpoints should also be network-restricted
 * (e.g., via Kubernetes NetworkPolicy or service mesh) so only internal
 * services can reach them.
 */
@Tag(name = "Internal - Tenant Quotas", description = "Internal endpoint for tenant quota lookups (service-to-service only)")
@RestController
@RequestMapping("/api/v1/internal/tenants")
public class InternalTenantController {

    private final TenantService tenantService;
    private final String internalApiKey;

    public InternalTenantController(TenantService tenantService,
                                     @Value("${atlas.internal.api-key}") String internalApiKey) {
        this.tenantService = tenantService;
        this.internalApiKey = internalApiKey;
    }

    @Operation(summary = "Get tenant quotas", description = "Return resource quotas configured for the given tenant")
    @ApiResponse(responseCode = "200", description = "Quotas returned")
    @ApiResponse(responseCode = "403", description = "Invalid or missing internal API key")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    @GetMapping("/{tenantId}/quotas")
    public ResponseEntity<TenantQuotaResponse> getQuotas(
            @PathVariable UUID tenantId,
            @RequestHeader(value = "X-Internal-Api-Key", required = false) String apiKey) {
        if (apiKey == null || !apiKey.equals(internalApiKey)) {
            return ResponseEntity.status(403).build();
        }
        return tenantService.findById(tenantId)
                .map(TenantQuotaResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
