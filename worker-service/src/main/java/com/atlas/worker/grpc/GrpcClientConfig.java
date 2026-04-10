package com.atlas.worker.grpc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures the gRPC client for step result reporting when the worker is configured
 * to use gRPC transport ({@code atlas.worker.result-transport=grpc}).
 *
 * <p>When this configuration is active, a {@link StepResultGrpcClient} bean is created
 * and the {@link com.atlas.worker.publisher.StepResultPublisher} will route results
 * through gRPC instead of Kafka.
 */
@Configuration
@ConditionalOnProperty(name = "atlas.worker.result-transport", havingValue = "grpc")
public class GrpcClientConfig {

    @Value("${atlas.grpc.client.workflow-host:localhost}")
    private String workflowHost;

    @Value("${atlas.grpc.client.workflow-port:9090}")
    private int workflowPort;

    @Bean(destroyMethod = "shutdown")
    public StepResultGrpcClient stepResultGrpcClient() {
        return new StepResultGrpcClient(workflowHost, workflowPort);
    }
}
