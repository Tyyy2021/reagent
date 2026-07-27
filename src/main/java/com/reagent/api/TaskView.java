package com.reagent.api;

import java.time.Instant;

public record TaskView(
        String taskId,
        String status,
        String goal,
        String result,
        String profile,
        int recoveryCount,
        String ownerId,
        long leaseEpoch,
        Instant createdAt,
        Instant updatedAt,
        IncidentSummaryView incident
) {
}
