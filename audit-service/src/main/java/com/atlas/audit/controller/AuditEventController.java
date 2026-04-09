package com.atlas.audit.controller;

import com.atlas.audit.dto.AuditEventResponse;
import com.atlas.audit.service.AuditQueryService;
import com.atlas.common.security.AuthenticatedPrincipal;
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
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

    private final AuditQueryService queryService;

    public AuditEventController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * GET /api/v1/audit-events
     *
     * <p>All query parameters are optional. Results are ordered newest-first and capped
     * at the requested {@code size} (default 20, max 100).
     *
     * @param principal    the authenticated caller; tenant_id is read from here
     * @param eventType    filter on event_type
     * @param resourceType filter on resource_type
     * @param resourceId   filter on resource_id
     * @param actorId      filter on actor_id
     * @param from         lower bound (inclusive) on occurred_at (ISO-8601)
     * @param to           upper bound (inclusive) on occurred_at (ISO-8601)
     * @param size         page size (default 20, max 100)
     */
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
