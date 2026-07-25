package com.reagent.approval;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApprovalDecisionRequest(
        @NotNull ApprovalDecision decision,
        @Size(max = 512) String reason
) {
}
