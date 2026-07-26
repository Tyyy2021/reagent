package com.reagent.acceptance;

import java.util.List;
import java.util.Objects;

public record AcceptanceEvidence(
        int contractVersion,
        String taskId,
        String profileId,
        String taskStatus,
        List<String> citationIds,
        List<String> citationSources,
        List<String> mcpTools,
        String approvalDecision,
        List<Long> workerEpochs,
        String ticketId,
        int createTicketAttempts,
        int uniqueTicketCount,
        boolean passed
) {
    public AcceptanceEvidence {
        taskId = Objects.requireNonNull(taskId, "taskId");
        profileId = Objects.requireNonNull(profileId, "profileId");
        taskStatus = Objects.requireNonNull(taskStatus, "taskStatus");
        citationIds = List.copyOf(Objects.requireNonNull(citationIds, "citationIds"));
        citationSources = List.copyOf(
                Objects.requireNonNull(citationSources, "citationSources"));
        mcpTools = List.copyOf(Objects.requireNonNull(mcpTools, "mcpTools"));
        approvalDecision = Objects.requireNonNull(
                approvalDecision, "approvalDecision");
        workerEpochs = List.copyOf(Objects.requireNonNull(workerEpochs, "workerEpochs"));
        ticketId = Objects.requireNonNull(ticketId, "ticketId");
    }
}
