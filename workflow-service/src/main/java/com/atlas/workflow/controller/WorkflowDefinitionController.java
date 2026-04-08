package com.atlas.workflow.controller;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.dto.CreateDefinitionRequest;
import com.atlas.workflow.dto.DefinitionResponse;
import com.atlas.workflow.service.WorkflowDefinitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflow-definitions")
public class WorkflowDefinitionController {

    private final WorkflowDefinitionService service;

    public WorkflowDefinitionController(WorkflowDefinitionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DefinitionResponse> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody CreateDefinitionRequest request) {

        var definition = service.create(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(DefinitionResponse.from(definition));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DefinitionResponse> getById(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var definition = service.getById(principal.tenantId(), id);
        return ResponseEntity.ok(DefinitionResponse.from(definition));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<DefinitionResponse> publish(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var definition = service.publish(principal.tenantId(), id);
        return ResponseEntity.ok(DefinitionResponse.from(definition));
    }
}
