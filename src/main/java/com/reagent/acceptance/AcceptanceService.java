package com.reagent.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.approval.ApprovalRequestEntity;
import com.reagent.approval.ApprovalRequestRepository;
import com.reagent.persist.EventRepository;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskNotFoundException;
import com.reagent.persist.TaskRepository;
import com.reagent.persist.TaskStatus;
import com.reagent.persist.ToolCallEntity;
import com.reagent.persist.ToolCallRepository;
import com.reagent.stream.TaskEvent;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public final class AcceptanceService {

    private static final List<String> MCP_TOOL_ORDER =
            List.of("query_metrics", "search_logs", "create_ticket");
    private static final Pattern SAFE_ID =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/#-]{0,255}$");
    private static final Pattern TICKET_ID = Pattern.compile("^OPS-[A-Z0-9]{12}$");
    private static final Pattern EPOCH_VALUE = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final int MAX_WORKER_EPOCHS = 16;

    private final TaskRepository tasks;
    private final ToolCallRepository calls;
    private final ApprovalRequestRepository approvals;
    private final EventRepository events;
    private final PythonAcceptanceClient python;
    private final ObjectMapper mapper;

    public AcceptanceService(
            TaskRepository tasks,
            ToolCallRepository calls,
            ApprovalRequestRepository approvals,
            EventRepository events,
            PythonAcceptanceClient python,
            ObjectMapper mapper
    ) {
        this.tasks = tasks;
        this.calls = calls;
        this.approvals = approvals;
        this.events = events;
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
        List<Long> epochs = workerEpochs(taskId);
        PythonAcceptanceClient.PythonAcceptanceResponse scoped = python.fetch(taskId);
        boolean passed = passed(task, approval, ticketId, scoped, epochs);
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

    private List<Long> workerEpochs(String taskId) {
        List<String> values;
        try {
            values = events.findOrderedDistinctEpochValues(
                    taskId,
                    TaskEvent.Type.TASK_STARTED.name(),
                    PageRequest.of(0, MAX_WORKER_EPOCHS + 1));
        } catch (RuntimeException failure) {
            throw invalidLedger();
        }
        if (values == null) {
            throw invalidLedger();
        }
        if (values.isEmpty()) {
            return List.of();
        }
        Set<Long> epochs = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || !EPOCH_VALUE.matcher(value).matches()) {
                throw invalidLedger();
            }
            long epoch;
            try {
                epoch = Long.parseLong(value);
            } catch (NumberFormatException failure) {
                throw invalidLedger();
            }
            if (epoch <= 0 || !epochs.add(epoch)) {
                throw invalidLedger();
            }
        }
        if (epochs.size() > MAX_WORKER_EPOCHS) {
            throw invalidLedger();
        }
        return List.copyOf(epochs);
    }

    private static boolean passed(
            TaskEntity task,
            String approval,
            String ticketId,
            PythonAcceptanceClient.PythonAcceptanceResponse scoped,
            List<Long> epochs
    ) {
        if (task.getStatus() != TaskStatus.COMPLETED || epochs.isEmpty()) {
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
