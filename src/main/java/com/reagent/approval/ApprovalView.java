package com.reagent.approval;

import java.time.Instant;

public record ApprovalView(
        String taskId,
        String toolCallId,
        int assistantMessageSeq,
        String toolName,
        ApprovalStatus status,
        String title,
        String severity,
        String evidencePreview,
        String decisionReason,
        Instant requestedAt,
        Instant decidedAt
) {
}
