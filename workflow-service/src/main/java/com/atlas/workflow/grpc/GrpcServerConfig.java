package com.atlas.workflow.grpc;

import com.atlas.workflow.service.StepResultProcessor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Configures and manages the gRPC server lifecycle for the Workflow Service.
 *
 * <p>Enabled only when {@code atlas.grpc.server.enabled=true}. The server listens on
 * the port specified by {@code atlas.grpc.server.port} (default 9090) and exposes the
 * {@link StepResultGrpcService} for workers to report results via gRPC.
 */
@Configuration
@ConditionalOnProperty(name = "atlas.grpc.server.enabled", havingValue = "true", matchIfMissing = false)
public class GrpcServerConfig {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerConfig.class);

    @Value("${atlas.grpc.server.port:9090}")
    private int grpcPort;

    private Server server;

    @Bean
    public StepResultGrpcService stepResultGrpcService(StepResultProcessor stepResultProcessor) {
        return new StepResultGrpcService(stepResultProcessor);
    }

    @EventListener(ContextRefreshedEvent.class)
    public void startGrpcServer(ContextRefreshedEvent event) throws IOException {
        StepResultGrpcService grpcService = event.getApplicationContext()
                .getBean(StepResultGrpcService.class);

        server = ServerBuilder.forPort(grpcPort)
                .addService(grpcService)
                .build()
                .start();

        log.info("gRPC server started on port {}", grpcPort);
    }

    @EventListener(ContextClosedEvent.class)
    public void stopGrpcServer() {
        if (server != null) {
            log.info("Shutting down gRPC server...");
            server.shutdown();
            try {
                if (!server.awaitTermination(10, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("gRPC server stopped");
        }
    }
}
