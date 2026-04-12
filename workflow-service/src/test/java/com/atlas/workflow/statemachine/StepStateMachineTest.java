package com.atlas.workflow.statemachine;

import com.atlas.workflow.domain.StepStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepStateMachineTest {

    @ParameterizedTest(name = "{0} -> {1} should be valid")
    @CsvSource({
            "PENDING,             LEASED",
            "LEASED,              RUNNING",
            "LEASED,              PENDING",
            "RUNNING,             SUCCEEDED",
            "RUNNING,             FAILED",
            "RUNNING,             WAITING",
            "WAITING,             SUCCEEDED",
            "WAITING,             FAILED",
            "FAILED,              RETRY_SCHEDULED",
            "FAILED,              DEAD_LETTERED",
            "FAILED,              COMPENSATING",
            "RETRY_SCHEDULED,     PENDING",
            "SUCCEEDED,           COMPENSATING",
            "COMPENSATING,        COMPENSATED",
            "COMPENSATING,        COMPENSATION_FAILED",
            "COMPENSATION_FAILED, DEAD_LETTERED",
            "DEAD_LETTERED,       PENDING"
    })
    void validTransitions(String from, String to) {
        StepStatus fromStatus = StepStatus.valueOf(from.trim());
        StepStatus toStatus = StepStatus.valueOf(to.trim());

        assertThat(StepStateMachine.canTransition(fromStatus, toStatus)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} should be invalid")
    @CsvSource({
            "PENDING,        RUNNING",
            "PENDING,        SUCCEEDED",
            "PENDING,        FAILED",
            "LEASED,         SUCCEEDED",
            "RUNNING,        LEASED",
            "RUNNING,        PENDING",
            "SUCCEEDED,      PENDING",
            "DEAD_LETTERED,  RUNNING",
            "COMPENSATED,    COMPENSATING",
            "COMPENSATED,    PENDING"
    })
    void invalidTransitions(String from, String to) {
        StepStatus fromStatus = StepStatus.valueOf(from.trim());
        StepStatus toStatus = StepStatus.valueOf(to.trim());

        assertThat(StepStateMachine.canTransition(fromStatus, toStatus)).isFalse();
    }

    @ParameterizedTest(name = "validate({0} -> {1}) should throw")
    @CsvSource({
            "PENDING,       RUNNING",
            "DEAD_LETTERED, RUNNING",
            "COMPENSATED,   COMPENSATING"
    })
    void validateThrowsOnInvalidTransition(String from, String to) {
        StepStatus fromStatus = StepStatus.valueOf(from.trim());
        StepStatus toStatus = StepStatus.valueOf(to.trim());

        assertThatThrownBy(() -> StepStateMachine.validate(fromStatus, toStatus))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid step transition");
    }
}
