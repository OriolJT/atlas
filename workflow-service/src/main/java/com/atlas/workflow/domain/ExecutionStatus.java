package com.atlas.workflow.domain;

public enum ExecutionStatus {
    PENDING,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED,
    COMPENSATION_FAILED,
    CANCELED,
    TIMED_OUT
}
