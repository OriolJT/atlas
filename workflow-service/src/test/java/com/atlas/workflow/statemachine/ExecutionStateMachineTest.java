package com.atlas.workflow.statemachine;

import com.atlas.workflow.domain.ExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionStateMachineTest {

    // -----------------------------------------------------------------------
    // Valid transitions
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1} should be allowed")
    @CsvSource({
            "PENDING,     RUNNING",
            "PENDING,     CANCELED",
            "RUNNING,     WAITING",
            "RUNNING,     COMPLETED",
            "RUNNING,     FAILED",
            "RUNNING,     CANCELED",
            "RUNNING,     TIMED_OUT",
            "WAITING,     RUNNING",
            "WAITING,     CANCELED",
            "WAITING,     TIMED_OUT",
            "FAILED,      COMPENSATING",
            "COMPENSATING,COMPENSATED",
            "COMPENSATING,COMPENSATION_FAILED"
    })
    void validTransitions_doNotThrow(String from, String to) {
        ExecutionStatus fromStatus = ExecutionStatus.valueOf(from.trim());
        ExecutionStatus toStatus   = ExecutionStatus.valueOf(to.trim());

        assertThat(ExecutionStateMachine.canTransition(fromStatus, toStatus)).isTrue();
        // validateTransition must not throw
        ExecutionStateMachine.validateTransition(fromStatus, toStatus);
    }

    // -----------------------------------------------------------------------
    // Invalid transitions
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1} should be rejected")
    @CsvSource({
            // Terminal states must not go anywhere
            "COMPLETED,          RUNNING",
            "COMPLETED,          PENDING",
            "COMPENSATED,        RUNNING",
            "COMPENSATION_FAILED,RUNNING",
            "CANCELED,           RUNNING",
            "TIMED_OUT,          RUNNING",
            // Non-terminal but disallowed cross-transitions
            "PENDING,            COMPLETED",
            "PENDING,            FAILED",
            "PENDING,            WAITING",
            "RUNNING,            PENDING",
            "RUNNING,            COMPENSATING",
            "WAITING,            COMPLETED",
            "WAITING,            FAILED",
            "FAILED,             RUNNING",
            "FAILED,             COMPLETED",
            "COMPENSATING,       RUNNING",
            "COMPENSATING,       FAILED"
    })
    void invalidTransitions_throwIllegalState(String from, String to) {
        ExecutionStatus fromStatus = ExecutionStatus.valueOf(from.trim());
        ExecutionStatus toStatus   = ExecutionStatus.valueOf(to.trim());

        assertThat(ExecutionStateMachine.canTransition(fromStatus, toStatus)).isFalse();

        assertThatThrownBy(() -> ExecutionStateMachine.validateTransition(fromStatus, toStatus))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(fromStatus.name())
                .hasMessageContaining(toStatus.name());
    }

    // -----------------------------------------------------------------------
    // canTransition helper — spot checks
    // -----------------------------------------------------------------------

    @Test
    void canTransition_pendingToRunning_isTrue() {
        assertThat(ExecutionStateMachine.canTransition(
                ExecutionStatus.PENDING, ExecutionStatus.RUNNING)).isTrue();
    }

    @Test
    void canTransition_completedToAnything_isFalse() {
        for (ExecutionStatus target : ExecutionStatus.values()) {
            assertThat(ExecutionStateMachine.canTransition(ExecutionStatus.COMPLETED, target))
                    .as("COMPLETED -> %s should be false", target)
                    .isFalse();
        }
    }

    @Test
    void canTransition_canceledToAnything_isFalse() {
        for (ExecutionStatus target : ExecutionStatus.values()) {
            assertThat(ExecutionStateMachine.canTransition(ExecutionStatus.CANCELED, target))
                    .as("CANCELED -> %s should be false", target)
                    .isFalse();
        }
    }
}
