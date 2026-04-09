package com.atlas.worker.lease;

public interface LeaseManager {
    boolean acquireLease(String stepExecutionId, String workerId, long timeoutSeconds);
    boolean releaseLease(String stepExecutionId, String workerId);
    boolean extendLease(String stepExecutionId, String workerId, long timeoutSeconds);
}
