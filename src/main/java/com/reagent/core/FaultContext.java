package com.reagent.core;

import java.util.Objects;
import java.util.Optional;

/** Immutable run identity observed by deterministic fault injection hooks. */
public record FaultContext(
        String taskId,
        String workerId,
        long leaseEpoch,
        Optional<String> toolCallId,
        Optional<Integer> batchSequence
) {
    public FaultContext {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId is required");
        }
        if (leaseEpoch < 0) {
            throw new IllegalArgumentException("leaseEpoch must be non-negative");
        }
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(batchSequence, "batchSequence");
        toolCallId.ifPresent(id -> {
            if (id.isBlank()) {
                throw new IllegalArgumentException("toolCallId must be non-blank when present");
            }
        });
        batchSequence.ifPresent(sequence -> {
            if (sequence < 0) {
                throw new IllegalArgumentException("batchSequence must be non-negative when present");
            }
        });
    }
}
