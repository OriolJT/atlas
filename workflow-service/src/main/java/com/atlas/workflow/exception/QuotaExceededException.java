package com.atlas.workflow.exception;

public class QuotaExceededException extends RuntimeException {

    private final String quotaType;
    private final long current;
    private final long limit;

    public QuotaExceededException(String quotaType, long current, long limit) {
        super("Quota exceeded for " + quotaType + ": current=" + current + ", limit=" + limit);
        this.quotaType = quotaType;
        this.current = current;
        this.limit = limit;
    }

    public String getQuotaType() {
        return quotaType;
    }

    public long getCurrent() {
        return current;
    }

    public long getLimit() {
        return limit;
    }
}
