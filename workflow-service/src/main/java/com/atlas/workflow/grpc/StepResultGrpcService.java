package com.atlas.workflow.grpc;

import com.atlas.grpc.workflow.v1.HeartbeatRequest;
import com.atlas.grpc.workflow.v1.HeartbeatResponse;
import com.atlas.grpc.workflow.v1.StepResultRequest;
import com.atlas.grpc.workflow.v1.StepResultResponse;
import com.atlas.grpc.workflow.v1.StepResultServiceGrpc;
import com.atlas.workflow.service.StepResultProcessor;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * gRPC server implementation for {@code StepResultService}.
 *
 * <p>Provides an alternative transport for workers to report step execution results
 * directly to the Workflow Service via gRPC instead of Kafka. The gRPC path converts
 * the protobuf request into the same {@code Map<String, Object>} payload the
 * {@link StepResultProcessor} already expects, so no changes to the processing logic are needed.
 *
 * <p>Kafka remains the default and primary transport. gRPC is an opt-in optimization
 * for lower-latency result reporting.
 */
public class StepResultGrpcService extends StepResultServiceGrpc.StepResultServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(StepResultGrpcService.class);

    private final StepResultProcessor stepResultProcessor;

    public StepResultGrpcService(StepResultProcessor stepResultProcessor) {
        this.stepResultProcessor = stepResultProcessor;
    }

    @Override
    public void reportStepResult(StepResultRequest request,
                                 StreamObserver<StepResultResponse> responseObserver) {
        log.debug("gRPC ReportStepResult received: stepExecutionId={} outcome={}",
                request.getStepExecutionId(), request.getOutcome());

        try {
            Map<String, Object> payload = toPayload(request);
            stepResultProcessor.process(payload);

            responseObserver.onNext(StepResultResponse.newBuilder()
                    .setAccepted(true)
                    .setMessage("processed")
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("gRPC ReportStepResult failed for stepExecutionId={}: {}",
                    request.getStepExecutionId(), e.getMessage(), e);

            responseObserver.onNext(StepResultResponse.newBuilder()
                    .setAccepted(false)
                    .setMessage(e.getMessage() != null ? e.getMessage() : "internal error")
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void reportWorkerHeartbeat(HeartbeatRequest request,
                                      StreamObserver<HeartbeatResponse> responseObserver) {
        log.debug("gRPC heartbeat from worker={} for stepExecutionId={}",
                request.getWorkerId(), request.getStepExecutionId());

        // Heartbeat acknowledgement — lease extension can be added here in the future
        responseObserver.onNext(HeartbeatResponse.newBuilder()
                .setAcknowledged(true)
                .build());
        responseObserver.onCompleted();
    }

    /**
     * Converts a protobuf {@link StepResultRequest} into the {@code Map<String, Object>}
     * format expected by {@link StepResultProcessor#process(Map)}.
     */
    private Map<String, Object> toPayload(StepResultRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("step_execution_id", request.getStepExecutionId());
        payload.put("execution_id", request.getExecutionId());
        payload.put("tenant_id", request.getTenantId());
        payload.put("outcome", request.getOutcome());
        payload.put("attempt", request.getAttempt());

        if (!request.getOutputJson().isEmpty()) {
            // Parse the JSON string into a Map for the processor
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = new tools.jackson.databind.ObjectMapper()
                        .readValue(request.getOutputJson(), Map.class);
                payload.put("output", output);
            } catch (Exception e) {
                log.warn("Failed to parse output_json for stepExecutionId={}, treating as raw string",
                        request.getStepExecutionId());
                payload.put("output", Map.of("raw", request.getOutputJson()));
            }
        }

        if (!request.getError().isEmpty()) {
            payload.put("error", request.getError());
        }

        payload.put("non_retryable", request.getNonRetryable());
        return payload;
    }
}
