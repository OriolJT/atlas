package com.atlas.identity.controller;

import com.atlas.identity.dto.TenantQuotaResponse;
import com.atlas.identity.service.TenantService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal/tenants")
public class InternalTenantController {

    private final TenantService tenantService;

    public InternalTenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping("/{tenantId}/quotas")
    public ResponseEntity<TenantQuotaResponse> getQuotas(@PathVariable UUID tenantId) {
        return tenantService.findById(tenantId)
                .map(TenantQuotaResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
