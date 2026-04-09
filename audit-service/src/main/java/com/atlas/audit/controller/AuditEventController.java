package com.atlas.audit.controller;

import com.atlas.audit.dto.AuditEventResponse;
import com.atlas.audit.service.AuditQueryService;
import com.atlas.common.security.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST API for querying audit events. All requests are automatically scoped to the
 * tenant extracted from the Bearer JWT — tenant_id is never accepted as a query param.
 */
@Tag(name = "Audit Events", description = "Query immutable audit event records scoped to the caller's tenant")
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

    private final AuditQueryService queryService;

    public AuditEventController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(
            summary = "Query audit events",
            description = "Return audit events for the caller's tenant. All filters are optional. " +
                    "Results are ordered newest-first and capped at the requested size (default 20, max 100).")
    @ApiResponse(responseCode = "200", description = "Audit events returned")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @GetMapping
    public ResponseEntity<List<AuditEventResponse>> getAuditEvents(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "resource_type", required = false) String resourceType,
            @RequestParam(name = "resource_id", required = false) UUID resourceId,
            @RequestParam(name = "actor_id", required = false) UUID actorId,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(name = "size", required = false) Integer size) {

        List<AuditEventResponse> results = queryService
                .findByTenant(principal.tenantId(), eventType, resourceType, resourceId,
                        actorId, from, to, size)
                .stream()
                .map(AuditEventResponse::from)
                .toList();

        return ResponseEntity.ok(results);
    }
}
