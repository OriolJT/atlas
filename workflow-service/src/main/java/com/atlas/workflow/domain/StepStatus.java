package com.atlas.workflow.domain;

public enum StepStatus {
    PENDING,
    LEASED,
    RUNNING,
    SUCCEEDED,
    WAITING,
    FAILED,
    RETRY_SCHEDULED,
    DEAD_LETTERED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED
}
