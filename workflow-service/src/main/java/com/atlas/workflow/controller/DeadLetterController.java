package com.atlas.workflow.controller;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.dto.DeadLetterResponse;
import com.atlas.workflow.service.DeadLetterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Dead Letter", description = "Inspect and replay failed workflow tasks")
@RestController
@RequestMapping("/api/v1/dead-letter")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    public DeadLetterController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @Operation(summary = "List dead-letter items", description = "Return all failed tasks in the dead-letter queue for the caller's tenant")
    @ApiResponse(responseCode = "200", description = "Dead-letter items returned")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @GetMapping
    public ResponseEntity<List<DeadLetterResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {

        List<DeadLetterResponse> items = deadLetterService.list(principal.tenantId())
                .stream()
                .map(DeadLetterResponse::from)
                .toList();

        return ResponseEntity.ok(items);
    }

    @Operation(summary = "Replay dead-letter item", description = "Requeue a failed task for re-execution")
    @ApiResponse(responseCode = "200", description = "Item requeued for replay")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "404", description = "Dead-letter item not found")
    @PostMapping("/{id}/replay")
    public ResponseEntity<DeadLetterResponse> replay(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var item = deadLetterService.replay(id, principal.tenantId());
        return ResponseEntity.ok(DeadLetterResponse.from(item));
    }
}
