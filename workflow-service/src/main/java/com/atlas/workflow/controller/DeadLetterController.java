package com.atlas.workflow.controller;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.dto.DeadLetterResponse;
import com.atlas.workflow.service.DeadLetterService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dead-letter")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    public DeadLetterController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @GetMapping
    public ResponseEntity<List<DeadLetterResponse>> list(
            @AuthenticationPrincipal AuthenticatedPrincipal principal) {

        List<DeadLetterResponse> items = deadLetterService.list(principal.tenantId())
                .stream()
                .map(DeadLetterResponse::from)
                .toList();

        return ResponseEntity.ok(items);
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<DeadLetterResponse> replay(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var item = deadLetterService.replay(id, principal.tenantId());
        return ResponseEntity.ok(DeadLetterResponse.from(item));
    }
}
