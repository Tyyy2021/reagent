package com.reagent.core;

public record TaskRunToken(String taskId, String workerId, long leaseEpoch) {
    public TaskRunToken {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        if (workerId == null || workerId.isBlank()) throw new IllegalArgumentException("workerId is required");
        if (leaseEpoch < 0) throw new IllegalArgumentException("leaseEpoch must be non-negative");
    }
}
