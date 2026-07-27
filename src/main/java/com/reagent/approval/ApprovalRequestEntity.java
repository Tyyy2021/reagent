package com.reagent.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "approval_request")
public class ApprovalRequestEntity {

    @Id
    private String toolCallId;

    private String taskId;

    private int assistantMessageSeq;

    @Column(length = 64)
    private String toolName;

    @Column(length = 1_000_000)
    private String argumentsSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ApprovalStatus status;

    @Column(length = 512)
    private String decisionReason;

    private Instant requestedAt;

    private Instant decidedAt;

    protected ApprovalRequestEntity() {
    }

    private ApprovalRequestEntity(
            String toolCallId,
            String taskId,
            int assistantMessageSeq,
            String toolName,
            String argumentsSnapshot,
            Instant requestedAt
    ) {
        this.toolCallId = toolCallId;
        this.taskId = taskId;
        this.assistantMessageSeq = assistantMessageSeq;
        this.toolName = toolName;
        this.argumentsSnapshot = argumentsSnapshot;
        this.status = ApprovalStatus.PENDING;
        this.requestedAt = requestedAt;
    }

    public static ApprovalRequestEntity pending(
            String toolCallId,
            String taskId,
            int assistantMessageSeq,
            String toolName,
            String argumentsSnapshot,
            Instant requestedAt
    ) {
        return new ApprovalRequestEntity(
                toolCallId,
                taskId,
                assistantMessageSeq,
                toolName,
                argumentsSnapshot,
                requestedAt);
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public String getTaskId() {
        return taskId;
    }

    public int getAssistantMessageSeq() {
        return assistantMessageSeq;
    }

    public String getToolName() {
        return toolName;
    }

    public String getArgumentsSnapshot() {
        return argumentsSnapshot;
    }

    public ApprovalStatus getStatus() {
        return status;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void decide(ApprovalStatus status, String reason, Instant now) {
        if (status == null || status == ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("A final approval status is required");
        }
        this.status = status;
        this.decisionReason = reason;
        this.decidedAt = now;
    }
}
