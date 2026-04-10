package com.atlas.worker.grpc;

import com.atlas.grpc.workflow.v1.HeartbeatRequest;
import com.atlas.grpc.workflow.v1.HeartbeatResponse;
import com.atlas.grpc.workflow.v1.StepResultRequest;
import com.atlas.grpc.workflow.v1.StepResultResponse;
import com.atlas.grpc.workflow.v1.StepResultServiceGrpc;
import com.atlas.worker.executor.StepResult;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * gRPC client for reporting step execution results directly to the Workflow Service.
 *
 * <p>This is an alternative to the Kafka-based {@code StepResultPublisher}. When configured
 * via {@code atlas.worker.result-transport=grpc}, the worker sends results over gRPC for
 * lower-latency delivery.
 *
 * <p>The client uses a blocking stub since step result publishing is already synchronous
 * (the worker waits for acknowledgement before releasing the lease).
 */
public class StepResultGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(StepResultGrpcClient.class);

    private final ManagedChannel channel;
    private final StepResultServiceGrpc.StepResultServiceBlockingV2Stub blockingStub;

    public StepResultGrpcClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.blockingStub = StepResultServiceGrpc.newBlockingV2Stub(channel);
        log.info("gRPC client connected to workflow-service at {}:{}", host, port);
    }

    /**
     * Reports a step execution result to the Workflow Service via gRPC.
     *
     * @param result   the step execution result
     * @param tenantId the tenant identifier
     * @throws RuntimeException if the gRPC call fails or the server rejects the result
     */
    public void reportResult(StepResult result, String tenantId) {
        StepResultRequest.Builder requestBuilder = StepResultRequest.newBuilder()
                .setStepExecutionId(result.stepExecutionId())
                .setTenantId(tenantId)
                .setOutcome(result.outcome().name())
                .setAttempt(result.attempt())
                .setNonRetryable(result.nonRetryable());

        if (result.output() != null) {
            try {
                String outputJson = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(result.output());
                requestBuilder.setOutputJson(outputJson);
            } catch (Exception e) {
                log.warn("Failed to serialize output to JSON for stepExecutionId={}",
                        result.stepExecutionId(), e);
            }
        }

        if (result.error() != null) {
            requestBuilder.setError(result.error());
        }

        StepResultResponse response = blockingStub.reportStepResult(requestBuilder.build());

        if (!response.getAccepted()) {
            throw new RuntimeException("gRPC result rejected for stepExecutionId="
                    + result.stepExecutionId() + ": " + response.getMessage());
        }

        log.debug("gRPC result accepted for stepExecutionId={} outcome={}",
                result.stepExecutionId(), result.outcome());
    }

    /**
     * Sends a heartbeat to the Workflow Service for a step currently being executed.
     *
     * @param workerId         the worker identifier
     * @param stepExecutionId  the step being executed
     */
    public void sendHeartbeat(String workerId, String stepExecutionId) {
        HeartbeatRequest request = HeartbeatRequest.newBuilder()
                .setWorkerId(workerId)
                .setStepExecutionId(stepExecutionId)
                .build();

        HeartbeatResponse response = blockingStub.reportWorkerHeartbeat(request);

        if (!response.getAcknowledged()) {
            log.warn("Heartbeat not acknowledged for worker={} stepExecutionId={}",
                    workerId, stepExecutionId);
        }
    }

    /**
     * Shuts down the gRPC channel gracefully.
     */
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("gRPC client channel shut down");
        }
    }
}
