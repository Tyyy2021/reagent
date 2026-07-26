package com.reagent.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.approval.ApprovalRequestEntity;
import com.reagent.approval.ApprovalRequestRepository;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskNotFoundException;
import com.reagent.persist.TaskRepository;
import com.reagent.persist.TaskStatus;
import com.reagent.persist.ToolCallEntity;
import com.reagent.persist.ToolCallRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

@Service
public final class AcceptanceService {

    private static final List<String> MCP_TOOL_ORDER =
            List.of("query_metrics", "search_logs", "create_ticket");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._:/#-]{1,256}$");
    private static final Pattern TICKET_ID = Pattern.compile("^OPS-[A-Z0-9]{12}$");

    private final TaskRepository tasks;
    private final ToolCallRepository calls;
    private final ApprovalRequestRepository approvals;
    private final PythonAcceptanceClient python;
    private final ObjectMapper mapper;

    public AcceptanceService(
            TaskRepository tasks,
            ToolCallRepository calls,
            ApprovalRequestRepository approvals,
            PythonAcceptanceClient python,
            ObjectMapper mapper
    ) {
        this.tasks = tasks;
        this.calls = calls;
        this.approvals = approvals;
        this.python = python;
        this.mapper = mapper;
    }

    public AcceptanceEvidence evidence(String taskId) {
        TaskEntity task = tasks.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        List<ToolCallEntity> ledger = calls.findAll().stream()
                .filter(call -> taskId.equals(call.getTaskId()))
                .toList();
        CitationProjection citations = citations(ledger);
        List<String> mcpTools = MCP_TOOL_ORDER.stream()
                .filter(name -> ledger.stream().anyMatch(call -> name.equals(call.getToolName())))
                .toList();
        String approval = approvalDecision(taskId);
        String ticketId = ticketId(ledger);
        PythonAcceptanceClient.PythonAcceptanceResponse scoped = python.fetch(taskId);
        List<Long> epochs = task.getLeaseEpoch() <= 0
                ? List.of()
                : LongStream.rangeClosed(1, Math.min(task.getLeaseEpoch(), 16))
                .boxed()
                .toList();
        boolean passed = passed(task, approval, ticketId, scoped);
        return new AcceptanceEvidence(
                1,
                task.getId(),
                safeProfile(task.getProfileId()),
                task.getStatus().name(),
                citations.ids(),
                citations.sources(),
                mcpTools,
                approval,
                epochs,
                ticketId,
                scoped.createTicketAttempts(),
                scoped.uniqueTicketCount(),
                passed);
    }

    private CitationProjection citations(List<ToolCallEntity> ledger) {
        Set<String> ids = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        ledger.stream()
                .filter(call -> "search_knowledge".equals(call.getToolName()))
                .map(ToolCallEntity::getResult)
                .filter(result -> result != null && result.startsWith("{"))
                .forEach(result -> {
                    JsonNode hits = parse(result).path("hits");
                    if (!hits.isArray()) {
                        throw invalidLedger();
                    }
                    for (JsonNode hit : hits) {
                        if (ids.size() >= 16) {
                            break;
                        }
                        String id = hit.path("chunkId").asText();
                        String source = hit.path("source").asText();
                        if (!SAFE_ID.matcher(id).matches()
                                || !SAFE_ID.matcher(source).matches()) {
                            throw invalidLedger();
                        }
                        ids.add(id);
                        sources.add(source);
                    }
                });
        return new CitationProjection(List.copyOf(ids), List.copyOf(sources));
    }

    private String approvalDecision(String taskId) {
        List<ApprovalRequestEntity> taskApprovals =
                approvals.findByTaskIdOrderByAssistantMessageSeqAscToolCallIdAsc(taskId);
        return taskApprovals.stream()
                .filter(item -> "create_ticket".equals(item.getToolName()))
                .map(item -> item.getStatus().name())
                .reduce((left, right) -> right)
                .orElse("NONE");
    }

    private String ticketId(List<ToolCallEntity> ledger) {
        for (ToolCallEntity call : ledger) {
            if (!"create_ticket".equals(call.getToolName())
                    || call.getResult() == null
                    || !call.getResult().startsWith("{")) {
                continue;
            }
            String ticketId = parse(call.getResult()).path("ticketId").asText();
            if (!TICKET_ID.matcher(ticketId).matches()) {
                throw invalidLedger();
            }
            return ticketId;
        }
        return "";
    }

    private static boolean passed(
            TaskEntity task,
            String approval,
            String ticketId,
            PythonAcceptanceClient.PythonAcceptanceResponse scoped
    ) {
        if (task.getStatus() != TaskStatus.COMPLETED) {
            return false;
        }
        if (ticketId.isEmpty()) {
            return scoped.createTicketAttempts() == 0
                    && scoped.uniqueTicketCount() == 0
                    && scoped.ticketIds().isEmpty()
                    && !"APPROVED".equals(approval);
        }
        return "APPROVED".equals(approval)
                && scoped.createTicketAttempts() >= 1
                && scoped.uniqueTicketCount() == 1
                && scoped.ticketIds().equals(List.of(ticketId));
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException failure) {
            throw invalidLedger();
        }
    }

    private static String safeProfile(String profile) {
        if (profile == null || !SAFE_ID.matcher(profile).matches()) {
            throw invalidLedger();
        }
        return profile;
    }

    private static IllegalStateException invalidLedger() {
        return new IllegalStateException("Acceptance ledger is invalid");
    }

    private record CitationProjection(List<String> ids, List<String> sources) {
    }
}
