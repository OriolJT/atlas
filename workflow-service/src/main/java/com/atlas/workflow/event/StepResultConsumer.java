package com.atlas.workflow.event;

import com.atlas.workflow.service.StepResultProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StepResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(StepResultConsumer.class);

    private final StepResultProcessor stepResultProcessor;

    public StepResultConsumer(StepResultProcessor stepResultProcessor) {
        this.stepResultProcessor = stepResultProcessor;
    }

    @KafkaListener(topics = "workflow.steps.result", groupId = "workflow-service")
    public void onStepResult(Map<String, Object> resultPayload) {
        log.debug("Received step result: {}", resultPayload);
        try {
            stepResultProcessor.process(resultPayload);
        } catch (Exception e) {
            log.error("Error processing step result: {}", e.getMessage(), e);
            throw e;
        }
    }
}
