package com.atlas.workflow.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared utility for parsing step definitions from workflow definition JSON.
 * Eliminates duplication between {@link WorkflowExecutionService} and {@link StepResultProcessor}.
 */
public final class StepDefinitionParser {

    private StepDefinitionParser() {
        // utility class
    }

    /**
     * Parses steps JSON (either a List or legacy Map format) into a list of step definition maps.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> parseSteps(Object stepsJson) {
        if (stepsJson == null) {
            return List.of();
        }
        if (stepsJson instanceof List<?> list) {
            return list.stream()
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        if (stepsJson instanceof Map<?, ?> map) {
            // Legacy Map format -- convert to List sorted by key
            List<Map.Entry<String, Object>> entries = new ArrayList<>(((Map<String, Object>) map).entrySet());
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            return entries.stream()
                    .map(e -> {
                        Map<String, Object> stepDef = new HashMap<>((Map<String, Object>) e.getValue());
                        stepDef.putIfAbsent("name", e.getKey());
                        return stepDef;
                    })
                    .toList();
        }
        return List.of();
    }

    /**
     * Extracts max_attempts from a step definition, checking retry_policy.max_attempts
     * and legacy maxAttempts key. Defaults to 1.
     */
    @SuppressWarnings("unchecked")
    public static int extractMaxAttempts(Map<String, Object> stepDef) {
        if (stepDef.containsKey("retry_policy")) {
            Map<String, Object> retryPolicy = (Map<String, Object>) stepDef.get("retry_policy");
            if (retryPolicy != null && retryPolicy.containsKey("max_attempts")) {
                return ((Number) retryPolicy.get("max_attempts")).intValue();
            }
        }
        // Fallback: support legacy "maxAttempts" key
        if (stepDef.containsKey("maxAttempts")) {
            return ((Number) stepDef.get("maxAttempts")).intValue();
        }
        return 1;
    }
}
