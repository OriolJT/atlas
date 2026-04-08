package com.atlas.workflow.controller;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.dto.ExecutionResponse;
import com.atlas.workflow.dto.SignalRequest;
import com.atlas.workflow.dto.StartExecutionRequest;
import com.atlas.workflow.dto.TimelineResponse;
import com.atlas.workflow.service.WorkflowExecutionService;
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
@RequestMapping("/api/v1/workflow-executions")
public class WorkflowExecutionController {

    private final WorkflowExecutionService service;

    public WorkflowExecutionController(WorkflowExecutionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ExecutionResponse> start(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody StartExecutionRequest request) {

        var execution = service.startExecution(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ExecutionResponse.from(execution));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExecutionResponse> getById(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var execution = service.getById(id, principal.tenantId());
        return ResponseEntity.ok(ExecutionResponse.from(execution));
    }

    @PostMapping("/{id}/signal")
    public ResponseEntity<ExecutionResponse> signal(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody SignalRequest request) {

        var execution = service.signal(id, principal.tenantId(), request);
        return ResponseEntity.ok(ExecutionResponse.from(execution));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ExecutionResponse> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var execution = service.cancel(id, principal.tenantId());
        return ResponseEntity.ok(ExecutionResponse.from(execution));
    }

    @GetMapping("/{id}/timeline")
    public ResponseEntity<TimelineResponse> timeline(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var timeline = service.getTimeline(id, principal.tenantId());
        return ResponseEntity.ok(timeline);
    }
}
