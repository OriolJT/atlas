package com.atlas.workflow.controller;

import com.atlas.common.security.AuthenticatedPrincipal;
import com.atlas.workflow.dto.ExecutionResponse;
import com.atlas.workflow.dto.SignalRequest;
import com.atlas.workflow.dto.StartExecutionRequest;
import com.atlas.workflow.dto.TimelineResponse;
import com.atlas.workflow.service.WorkflowExecutionService;
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

@Tag(name = "Workflow Executions", description = "Start, inspect, signal, and cancel workflow executions")
@RestController
@RequestMapping("/api/v1/workflow-executions")
public class WorkflowExecutionController {

    private final WorkflowExecutionService service;

    public WorkflowExecutionController(WorkflowExecutionService service) {
        this.service = service;
    }

    @Operation(summary = "Start execution", description = "Create and start a new workflow execution from a published definition")
    @ApiResponse(responseCode = "201", description = "Execution started")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "404", description = "Workflow definition not found or not published")
    @PostMapping
    public ResponseEntity<ExecutionResponse> start(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @Valid @RequestBody StartExecutionRequest request) {

        var execution = service.startExecution(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ExecutionResponse.from(execution));
    }

    @Operation(summary = "Get execution", description = "Retrieve the current state of a workflow execution")
    @ApiResponse(responseCode = "200", description = "Execution found")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "404", description = "Execution not found")
    @GetMapping("/{id}")
    public ResponseEntity<ExecutionResponse> getById(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var execution = service.getById(id, principal.tenantId());
        return ResponseEntity.ok(ExecutionResponse.from(execution));
    }

    @Operation(summary = "Send signal", description = "Deliver an external signal to a running workflow execution")
    @ApiResponse(responseCode = "200", description = "Signal delivered")
    @ApiResponse(responseCode = "400", description = "Invalid signal payload")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "404", description = "Execution not found")
    @ApiResponse(responseCode = "409", description = "Execution is not in a state that accepts signals")
    @PostMapping("/{id}/signal")
    public ResponseEntity<ExecutionResponse> signal(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody SignalRequest request) {

        var execution = service.signal(id, principal.tenantId(), request);
        return ResponseEntity.ok(ExecutionResponse.from(execution));
    }

    @Operation(summary = "Cancel execution", description = "Request cancellation of a running workflow execution")
    @ApiResponse(responseCode = "200", description = "Execution cancelled")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "404", description = "Execution not found")
    @ApiResponse(responseCode = "409", description = "Execution is already in a terminal state")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ExecutionResponse> cancel(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var execution = service.cancel(id, principal.tenantId());
        return ResponseEntity.ok(ExecutionResponse.from(execution));
    }

    @Operation(summary = "Get execution timeline", description = "Retrieve the ordered sequence of state transitions for a workflow execution")
    @ApiResponse(responseCode = "200", description = "Timeline returned")
    @ApiResponse(responseCode = "401", description = "Unauthorized")
    @ApiResponse(responseCode = "404", description = "Execution not found")
    @GetMapping("/{id}/timeline")
    public ResponseEntity<TimelineResponse> timeline(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        var timeline = service.getTimeline(id, principal.tenantId());
        return ResponseEntity.ok(timeline);
    }
}
