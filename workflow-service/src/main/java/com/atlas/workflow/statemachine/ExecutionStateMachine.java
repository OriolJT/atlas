package com.atlas.workflow.statemachine;

import com.atlas.workflow.domain.ExecutionStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class ExecutionStateMachine {

    private static final Map<ExecutionStatus, Set<ExecutionStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(ExecutionStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(ExecutionStatus.PENDING,
                EnumSet.of(ExecutionStatus.RUNNING, ExecutionStatus.CANCELED));

        ALLOWED_TRANSITIONS.put(ExecutionStatus.RUNNING,
                EnumSet.of(ExecutionStatus.WAITING, ExecutionStatus.COMPLETED,
                           ExecutionStatus.FAILED,  ExecutionStatus.CANCELED,
                           ExecutionStatus.TIMED_OUT));

        ALLOWED_TRANSITIONS.put(ExecutionStatus.WAITING,
                EnumSet.of(ExecutionStatus.RUNNING, ExecutionStatus.CANCELED,
                           ExecutionStatus.TIMED_OUT));

        ALLOWED_TRANSITIONS.put(ExecutionStatus.FAILED,
                EnumSet.of(ExecutionStatus.COMPENSATING));

        ALLOWED_TRANSITIONS.put(ExecutionStatus.COMPENSATING,
                EnumSet.of(ExecutionStatus.COMPENSATED, ExecutionStatus.COMPENSATION_FAILED));

        // Terminal states — no outgoing transitions
        ALLOWED_TRANSITIONS.put(ExecutionStatus.COMPLETED,          EnumSet.noneOf(ExecutionStatus.class));
        ALLOWED_TRANSITIONS.put(ExecutionStatus.COMPENSATED,        EnumSet.noneOf(ExecutionStatus.class));
        ALLOWED_TRANSITIONS.put(ExecutionStatus.COMPENSATION_FAILED, EnumSet.noneOf(ExecutionStatus.class));
        ALLOWED_TRANSITIONS.put(ExecutionStatus.CANCELED,           EnumSet.noneOf(ExecutionStatus.class));
        ALLOWED_TRANSITIONS.put(ExecutionStatus.TIMED_OUT,          EnumSet.noneOf(ExecutionStatus.class));
    }

    private ExecutionStateMachine() {
        // utility class
    }

    /**
     * Returns true if the transition from {@code from} to {@code to} is allowed.
     */
    public static boolean canTransition(ExecutionStatus from, ExecutionStatus to) {
        Set<ExecutionStatus> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Validates that the transition from {@code from} to {@code to} is allowed,
     * throwing {@link IllegalStateException} if not.
     */
    public static void validateTransition(ExecutionStatus from, ExecutionStatus to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException(
                    "Invalid workflow execution transition: " + from + " -> " + to);
        }
    }
}
