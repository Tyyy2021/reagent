package com.reagent.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.ToolCall;
import com.reagent.persist.MessageEntity;
import com.reagent.persist.MessageRepository;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskNotFoundException;
import com.reagent.persist.TaskRepository;
import com.reagent.persist.TaskStatus;
import com.reagent.persist.ToolCallEntity;
import com.reagent.persist.ToolCallStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class ApprovalDecisionTransaction {

    private static final String REJECTION_RESULT =
            "Approval rejected for create_ticket; no remote action occurred.";
    private static final String CANCELLATION_REASON = "task-cancelled";
    private static final String CANCELLATION_NOTE = "任务已被用户取消。";
    private static final String TRUNCATION_MARKER = "…[truncated]";

    private final TaskRepository taskRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApprovalDecisionTransaction(
            TaskRepository taskRepository,
            ApprovalRequestRepository approvalRepository,
            MessageRepository messageRepository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.approvalRepository = approvalRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ApprovalView> list(String taskId) {
        if (!taskRepository.existsById(taskId)) {
            throw new TaskNotFoundException(taskId);
        }
        return approvalRepository
                .findByTaskIdOrderByAssistantMessageSeqAscToolCallIdAsc(taskId)
                .stream()
                .map(this::toView)
                .toList();
    }

    @Transactional
    public DecisionOutcome decide(
            String taskId,
            String toolCallId,
            ApprovalDecisionRequest request
    ) {
        TaskEntity task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        ApprovalRequestEntity approval =
                approvalRepository.findForUpdate(taskId, toolCallId)
                        .orElseThrow(() -> new TaskNotFoundException(taskId));
        ToolCallEntity toolCall =
                approvalRepository.findToolCallForUpdate(taskId, toolCallId)
                        .orElseThrow(() -> new TaskNotFoundException(taskId));

        if (isTerminal(task.getStatus())) {
            throw new ApprovalConflictException(
                    "Approval cannot be decided for a terminal task");
        }

        ApprovalStatus desired = switch (request.decision()) {
            case APPROVE -> ApprovalStatus.APPROVED;
            case REJECT -> ApprovalStatus.REJECTED;
        };
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            if (approval.getStatus() == desired) {
                return new DecisionOutcome(
                        toView(approval), false, task.getLeaseEpoch());
            }
            throw new ApprovalConflictException(
                    "Approval was already decided differently");
        }

        if (task.getStatus() != TaskStatus.WAITING_APPROVAL
                || toolCall.getStatus() != ToolCallStatus.PENDING) {
            throw new ApprovalConflictException(
                    "Approval is not pending for a waiting task");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        approval.decide(desired, request.reason(), now);
        approvalRepository.saveAndFlush(approval);

        if (desired == ApprovalStatus.REJECTED) {
            int updated = approvalRepository.completePendingToolCall(
                    taskId,
                    toolCallId,
                    ToolCallStatus.REJECTED,
                    REJECTION_RESULT,
                    now);
            if (updated != 1) {
                throw new ApprovalConflictException(
                        "Tool call is no longer pending");
            }
        }

        boolean shouldResume =
                approvalRepository.countByTaskIdAndStatus(
                        taskId, ApprovalStatus.PENDING) == 0;
        if (shouldResume) {
            task.markRunning(now);
            task.releaseLease();
            taskRepository.save(task);
        }

        return new DecisionOutcome(
                toView(approval), shouldResume, task.getLeaseEpoch());
    }

    @Transactional
    public boolean cancelWaiting(String taskId) {
        TaskEntity task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.getStatus() == TaskStatus.RUNNING) {
            if (taskRepository.requestControl(taskId, "CANCEL") != 1) {
                throw new IllegalStateException(
                        "Running task cancellation signal was not persisted");
            }
            return false;
        }
        if (task.getStatus() != TaskStatus.WAITING_APPROVAL) {
            return false;
        }

        List<ApprovalRequestEntity> approvals =
                approvalRepository.findAllForUpdate(taskId);
        List<ApprovalRequestEntity> pendingApprovals = approvals.stream()
                .filter(approval -> approval.getStatus() == ApprovalStatus.PENDING)
                .toList();
        if (pendingApprovals.isEmpty()) {
            throw new ApprovalConflictException(
                    "Waiting task has no pending approval");
        }

        List<ToolCall> blockedBatch =
                loadBlockedBatch(taskId, pendingApprovals);
        List<ToolCallEntity> lockedCalls = blockedBatch.stream()
                .map(call -> approvalRepository
                        .findToolCallForUpdate(taskId, call.id())
                        .orElseThrow(() -> new IllegalStateException(
                                "Blocked batch tool call is missing")))
                .toList();
        validateBlockedBatch(blockedBatch, lockedCalls,
                pendingApprovals.getFirst().getAssistantMessageSeq());

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        pendingApprovals.forEach(approval ->
                approval.decide(ApprovalStatus.REJECTED,
                        CANCELLATION_REASON, now));
        approvalRepository.saveAllAndFlush(pendingApprovals);

        int sequence = messageRepository.nextSequenceForLockedTask(taskId);
        for (int index = 0; index < blockedBatch.size(); index++) {
            ToolCallEntity ledger = lockedCalls.get(index);
            ToolCall call = blockedBatch.get(index);
            String result;
            if (ledger.getStatus() == ToolCallStatus.PENDING) {
                result = cancellationResult(call.name());
                if (approvalRepository.completePendingToolCall(
                        taskId,
                        call.id(),
                        ToolCallStatus.REJECTED,
                        result,
                        now) != 1) {
                    throw new ApprovalConflictException(
                            "Tool call is no longer pending");
                }
            } else if (ledger.getStatus() == ToolCallStatus.REJECTED
                    && ledger.getResult() != null) {
                result = ledger.getResult();
            } else {
                throw new IllegalStateException(
                        "Waiting approval batch contains an executed or malformed tool call");
            }
            if (messageRepository.existsByTaskIdAndRoleAndToolCallId(
                    taskId, "tool", call.id())) {
                continue;
            }
            messageRepository.save(new MessageEntity(
                    taskId,
                    sequence++,
                    "tool",
                    result,
                    null,
                    call.id(),
                    now));
        }

        task.cancel(CANCELLATION_NOTE, now);
        task.clearControlSignal();
        taskRepository.save(task);
        return true;
    }

    private List<ToolCall> loadBlockedBatch(
            String taskId,
            List<ApprovalRequestEntity> pendingApprovals
    ) {
        int assistantSequence =
                pendingApprovals.getFirst().getAssistantMessageSeq();
        if (pendingApprovals.stream().anyMatch(
                approval ->
                        approval.getAssistantMessageSeq() != assistantSequence)) {
            throw new IllegalStateException(
                    "Waiting task spans multiple assistant batches");
        }
        MessageEntity assistant = messageRepository
                .findByTaskIdOrderByIdAsc(taskId)
                .stream()
                .filter(message ->
                        message.getSeq() == assistantSequence
                                && "assistant".equals(message.getRole())
                                && message.getToolCallsJson() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Blocked assistant batch is missing"));
        try {
            Object rawCalls = objectMapper.readValue(
                    assistant.getToolCallsJson(), Object.class);
            List<ToolCall> calls =
                    ToolCall.parseAssistantToolCalls(rawCalls);
            Set<String> callIds = new HashSet<>();
            calls.forEach(call -> callIds.add(call.id()));
            if (pendingApprovals.stream().anyMatch(
                    approval -> !callIds.contains(
                            approval.getToolCallId()))) {
                throw new IllegalStateException(
                        "Pending approval is outside the blocked batch");
            }
            return calls;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Blocked assistant batch is malformed");
        }
    }

    private void validateBlockedBatch(
            List<ToolCall> calls,
            List<ToolCallEntity> ledgers,
            int assistantSequence
    ) {
        for (int index = 0; index < calls.size(); index++) {
            ToolCall call = calls.get(index);
            ToolCallEntity ledger = ledgers.get(index);
            if (ledger.getAssistantMessageSeq() == null
                    || ledger.getAssistantMessageSeq() != assistantSequence
                    || !call.name().equals(ledger.getToolName())
                    || !call.arguments().equals(ledger.getArguments())) {
                throw new IllegalStateException(
                        "Blocked batch does not match the durable ledger");
            }
        }
    }

    private String cancellationResult(String toolName) {
        return "Task cancelled before " + toolName
                + "; no remote action occurred.";
    }

    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }

    private ApprovalView toView(ApprovalRequestEntity approval) {
        ApprovalProjection projection =
                projectCreateTicket(approval.getToolName(),
                        approval.getArgumentsSnapshot());
        return new ApprovalView(
                approval.getTaskId(),
                approval.getToolCallId(),
                approval.getAssistantMessageSeq(),
                approval.getToolName(),
                approval.getStatus(),
                projection.title(),
                projection.severity(),
                projection.evidencePreview(),
                approval.getDecisionReason(),
                approval.getRequestedAt(),
                approval.getDecidedAt());
    }

    private ApprovalProjection projectCreateTicket(
            String toolName,
            String argumentsSnapshot
    ) {
        try {
            JsonNode arguments = objectMapper.readTree(argumentsSnapshot);
            if (!"create_ticket".equals(toolName)
                    || arguments == null
                    || !arguments.isObject()
                    || !isText(arguments, "title")
                    || !isText(arguments, "severity")
                    || !isText(arguments, "evidence")) {
                throw malformedStoredPayload();
            }
            return new ApprovalProjection(
                    truncate(arguments.get("title").textValue(), 255, null),
                    truncate(arguments.get("severity").textValue(), 16, null),
                    truncate(
                            arguments.get("evidence").textValue(),
                            512,
                            TRUNCATION_MARKER));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw malformedStoredPayload();
        }
    }

    private boolean isText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual();
    }

    private String truncate(String value, int limit, String marker) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= limit) {
            return value;
        }
        if (marker == null) {
            return value.substring(
                    0, value.offsetByCodePoints(0, limit));
        }
        int markerCodePoints =
                marker.codePointCount(0, marker.length());
        int prefixCodePoints = limit - markerCodePoints;
        return value.substring(
                0, value.offsetByCodePoints(0, prefixCodePoints)) + marker;
    }

    private IllegalStateException malformedStoredPayload() {
        return new IllegalStateException(
                "Stored approval payload has unexpected shape");
    }

    private record ApprovalProjection(
            String title,
            String severity,
            String evidencePreview
    ) {
    }

    public record DecisionOutcome(
            ApprovalView view,
            boolean shouldResume,
            long leaseEpoch
    ) {
    }
}
