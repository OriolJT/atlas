package com.atlas.workflow.controller;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.dto.CreateDefinitionRequest;
import com.atlas.workflow.dto.DefinitionResponse;
import com.atlas.workflow.service.WorkflowDefinitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Workflow Definitions", description = "Create, retrieve, and publish workflow definitions")
@RestController
@RequestMapping("/api/v1/workflow-definitions")
public class WorkflowDefinitionController {

    private final WorkflowDefinitionService service;

    public WorkflowDefinitionController(WorkflowDefinitionService service) {
        this.service = service;
    }

    @Operation(summary = "Create workflow definition", description = "Create a new workflow definition in DRAFT state for the caller's tenant")
    @ApiResponse(responseCode = "201", description = "Workflow definition created")
    @ApiResponse(responseCode = "400", description = "Invalid definition schema")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @PostMapping
    public ResponseEntity<DefinitionResponse> create(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody CreateDefinitionRequest request) {

        var definition = service.create(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(DefinitionResponse.from(definition));
    }

    @Operation(summary = "Get workflow definition", description = "Retrieve a workflow definition by UUID, scoped to the caller's tenant")
    @ApiResponse(responseCode = "200", description = "Workflow definition found")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "404", description = "Workflow definition not found")
    @GetMapping("/{id}")
    public ResponseEntity<DefinitionResponse> getById(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var definition = service.getById(principal.tenantId(), id);
        return ResponseEntity.ok(DefinitionResponse.from(definition));
    }

    @Operation(summary = "Publish workflow definition", description = "Transition a DRAFT workflow definition to PUBLISHED, making it available for execution")
    @ApiResponse(responseCode = "200", description = "Workflow definition published")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "404", description = "Workflow definition not found")
    @ApiResponse(responseCode = "409", description = "Definition is not in DRAFT state")
    @PostMapping("/{id}/publish")
    public ResponseEntity<DefinitionResponse> publish(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var definition = service.publish(principal.tenantId(), id);
        return ResponseEntity.ok(DefinitionResponse.from(definition));
    }
}
