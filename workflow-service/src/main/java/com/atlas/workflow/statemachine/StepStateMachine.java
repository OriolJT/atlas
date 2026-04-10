package com.atlas.workflow.statemachine;

import com.atlas.workflow.domain.StepStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public final class StepStateMachine {

    private static final Map<StepStatus, Set<StepStatus>> ALLOWED_TRANSITIONS = Map.ofEntries(
            Map.entry(StepStatus.PENDING,             Set.of(StepStatus.LEASED)),
            Map.entry(StepStatus.LEASED,              Set.of(StepStatus.RUNNING, StepStatus.PENDING)),
            Map.entry(StepStatus.RUNNING,             Set.of(StepStatus.SUCCEEDED, StepStatus.FAILED, StepStatus.WAITING)),
            Map.entry(StepStatus.WAITING,             Set.of(StepStatus.SUCCEEDED, StepStatus.FAILED)),
            Map.entry(StepStatus.FAILED,              Set.of(StepStatus.RETRY_SCHEDULED, StepStatus.DEAD_LETTERED, StepStatus.COMPENSATING)),
            Map.entry(StepStatus.RETRY_SCHEDULED,     Set.of(StepStatus.PENDING)),
            Map.entry(StepStatus.SUCCEEDED,           Set.of(StepStatus.COMPENSATING)),
            Map.entry(StepStatus.COMPENSATING,        Set.of(StepStatus.COMPENSATED, StepStatus.COMPENSATION_FAILED)),
            Map.entry(StepStatus.COMPENSATION_FAILED, Set.of(StepStatus.DEAD_LETTERED)),
            Map.entry(StepStatus.DEAD_LETTERED,       Set.of(StepStatus.PENDING)),
            Map.entry(StepStatus.COMPENSATED,         Set.of())
    );

    public static boolean canTransition(StepStatus from, StepStatus to) {
        Set<StepStatus> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static void validate(StepStatus from, StepStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "Invalid step transition: " + from + " -> " + to);
        }
    }
}
