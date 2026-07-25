package com.reagent.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.ReAgentApplication;
import com.reagent.core.Context;
import com.reagent.core.AgentRunner;
import com.reagent.core.BatchDisposition;
import com.reagent.core.Decision;
import com.reagent.core.DefaultToolBatchCoordinator;
import com.reagent.core.FaultInjector;
import com.reagent.core.TaskControl;
import com.reagent.core.TaskRunToken;
import com.reagent.core.ToolCall;
import com.reagent.llm.LlmClient;
import com.reagent.mcp.McpCallResult;
import com.reagent.mcp.McpGateway;
import com.reagent.mcp.McpRemoteTool;
import com.reagent.persist.StateStore;
import com.reagent.persist.MessageEntity;
import com.reagent.persist.MessageRepository;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskNotFoundException;
import com.reagent.persist.TaskStatus;
import com.reagent.persist.ToolCallStatus;
import com.reagent.profile.AgentProfileDefinition;
import com.reagent.profile.SchemaHasher;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.TaskToolCatalog;
import com.reagent.profile.ToolCatalogResolver;
import com.reagent.testsupport.InfrastructureIT;
import com.reagent.testsupport.RecordingTool;
import com.reagent.testsupport.ScriptedLlmClient;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolExecutor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ApprovalServiceIT extends InfrastructureIT {

    @Autowired
    private StateStore stateStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ApprovalRequestRepository approvalRepository;

    @Autowired
    private ApprovalDecisionTransaction decisionTransaction;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ApprovalService approvalService;

    @MockBean
    private AgentRunner runner;

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private DefaultToolBatchCoordinator toolBatchCoordinator;

    @Autowired
    private TaskControl taskControl;

    @BeforeEach
    void clearRuntimeState() {
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM approval_request");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM task");
    }

    @AfterEach
    void clearRuntimeStateAfterTest() {
        clearRuntimeState();
    }

    @Test
    void taskStatusIncludesWaitingApproval() {
        assertEquals("WAITING_APPROVAL", TaskStatus.valueOf("WAITING_APPROVAL").name());
    }

    @Test
    void toolCallStatusIncludesRejected() {
        assertEquals("REJECTED", ToolCallStatus.valueOf("REJECTED").name());
    }

    @Test
    void rejectedToolMessageIsRecoverableFromDurableContext() {
        TaskEntity task = stateStore.createTask("approve ticket", "system");
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        stateStore.appendAssistant(token, Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "id", "call-rejected",
                        "type", "function",
                        "function", Map.of(
                                "name", "create_ticket",
                                "arguments", "{\"title\":\"Database unavailable\","
                                        + "\"severity\":\"sev1\",\"evidence\":\"pool exhausted\"}")))));
        String rejection = "Approval rejected; no remote action occurred.";
        jdbc.update("""
                UPDATE tool_call
                   SET status = 'REJECTED', result = ?, completed_at = ?
                 WHERE id = 'call-rejected'
                """, rejection, Timestamp.from(Instant.parse("2026-07-25T00:00:00Z")));
        jdbc.update("""
                INSERT INTO message
                    (task_id, seq, role, content, tool_calls_json, tool_call_id, created_at)
                VALUES (?, 3, 'tool', ?, NULL, 'call-rejected', ?)
                """, task.getId(), rejection, Timestamp.from(Instant.parse("2026-07-25T00:00:00Z")));
        entityManager.clear();

        Context restored = stateStore.loadContext(task.getId());

        assertEquals(List.of("system", "user", "assistant", "tool"),
                restored.messages().stream().map(message -> message.get("role")).toList());
        assertEquals("call-rejected", restored.messages().getLast().get("tool_call_id"));
        assertEquals(rejection, restored.messages().getLast().get("content"));
        assertEquals(List.of(), restored.pendingToolCalls());
        assertEquals("REJECTED", stateStore.statusOf(task.getId(), "call-rejected").name());
    }

    @Test
    void durableBatchBarrierIsIdempotentAndReleasesTheLeaseBeforeExecution() {
        RecordingTool ticket = new RecordingTool(
                "create_ticket",
                IdempotencyClass.IDEMPOTENT,
                ApprovalPolicy.REQUIRE_APPROVAL,
                "must not execute");
        TaskToolCatalog catalog = catalog(ticket);
        TaskEntity task = stateStore.createTask("approve ticket", "system");
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        ToolCall call = new ToolCall(
                "call-pending-approval",
                ticket.name(),
                "{\"title\":\"Database unavailable\",\"severity\":\"sev1\","
                        + "\"evidence\":\"pool exhausted\"}");
        stateStore.appendAssistant(token, Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of(
                                "name", call.name(),
                                "arguments", call.arguments())))));

        boolean first = stateStore.prepareApprovalBarrier(token, catalog, List.of(call));
        boolean repeated = stateStore.prepareApprovalBarrier(token, catalog, List.of(call));

        entityManager.clear();
        TaskEntity waiting = stateStore.getTask(task.getId());
        assertEquals(true, first);
        assertEquals(true, repeated);
        assertEquals(TaskStatus.WAITING_APPROVAL, waiting.getStatus());
        assertEquals(null, waiting.getOwnerId());
        assertEquals(null, waiting.getLeaseExpiresAt());
        assertEquals(ToolCallStatus.PENDING, stateStore.statusOf(task.getId(), call.id()));
        assertEquals(1, approvalRepository.count());
        ApprovalRequestEntity approval = approvalRepository.findById(call.id()).orElseThrow();
        assertEquals(task.getId(), approval.getTaskId());
        assertEquals(2, approval.getAssistantMessageSeq());
        assertEquals(ticket.name(), approval.getToolName());
        assertEquals(call.arguments(), approval.getArgumentsSnapshot());
        assertEquals(ApprovalStatus.PENDING, approval.getStatus());
        assertEquals(0, ticket.callCount());
    }

    @Test
    void approveCommitsRunningStateThenSameReplayIsNoOpAndOppositeConflicts() {
        PendingApproval pending = pendingApproval(
                "call-approve",
                "{\"title\":\"Database unavailable\",\"severity\":\"sev1\","
                        + "\"evidence\":\"pool exhausted\"}");
        ApprovalDecisionRequest approve =
                new ApprovalDecisionRequest(ApprovalDecision.APPROVE, "confirmed");

        ApprovalDecisionTransaction.DecisionOutcome first =
                decisionTransaction.decide(
                        pending.task().getId(), pending.call().id(), approve);
        ApprovalDecisionTransaction.DecisionOutcome replay =
                decisionTransaction.decide(
                        pending.task().getId(), pending.call().id(), approve);

        entityManager.clear();
        assertEquals(ApprovalStatus.APPROVED, first.view().status());
        assertEquals("confirmed", first.view().decisionReason());
        assertTrue(first.shouldResume());
        assertFalse(replay.shouldResume());
        assertEquals(first.view(), replay.view());
        assertEquals(TaskStatus.RUNNING,
                stateStore.getTask(pending.task().getId()).getStatus());
        assertEquals(null, stateStore.getTask(pending.task().getId()).getOwnerId());
        assertEquals(ToolCallStatus.PENDING,
                stateStore.statusOf(pending.task().getId(), pending.call().id()));
        assertEquals(List.of(),
                messageRepository.findByTaskIdOrderByIdAsc(pending.task().getId()).stream()
                        .filter(message -> "tool".equals(message.getRole()))
                        .toList());
        assertThrows(
                ApprovalConflictException.class,
                () -> decisionTransaction.decide(
                        pending.task().getId(),
                        pending.call().id(),
                        new ApprovalDecisionRequest(ApprovalDecision.REJECT, "changed")));
    }

    @Test
    void rejectDurablyRecordsThenResumeWritesOneOrderedBoundedSyntheticMessage(
            @TempDir Path workspace
    ) {
        String rawSentinel = "RAW_EVIDENCE_SENTINEL";
        PendingApproval pending = pendingApproval(
                "call-reject",
                "{\"title\":\"Database unavailable\",\"severity\":\"sev1\","
                        + "\"evidence\":\"" + rawSentinel + "\"}");

        ApprovalDecisionTransaction.DecisionOutcome rejected =
                decisionTransaction.decide(
                        pending.task().getId(),
                        pending.call().id(),
                        new ApprovalDecisionRequest(
                                ApprovalDecision.REJECT, "insufficient evidence"));

        entityManager.clear();
        assertEquals(ApprovalStatus.REJECTED, rejected.view().status());
        assertTrue(rejected.shouldResume());
        assertEquals(ToolCallStatus.REJECTED,
                stateStore.statusOf(pending.task().getId(), pending.call().id()));
        assertEquals(
                "Approval rejected for create_ticket; no remote action occurred.",
                jdbc.queryForObject(
                        "SELECT result FROM tool_call WHERE id = ?",
                        String.class,
                        pending.call().id()));
        assertEquals(
                List.of(),
                messageRepository
                        .findByTaskIdOrderByIdAsc(pending.task().getId()).stream()
                        .filter(message -> "tool".equals(message.getRole()))
                        .toList(),
                "The coordinator must materialize synthetic results in assistant order");

        RecordingTool ticket = new RecordingTool(
                "create_ticket",
                IdempotencyClass.IDEMPOTENT,
                ApprovalPolicy.REQUIRE_APPROVAL,
                "must not execute");
        TaskToolCatalog catalog = catalog(ticket);
        TaskRunToken resumed =
                stateStore.claim(pending.task().getId()).orElseThrow();
        Context restored = stateStore.loadContext(pending.task().getId());
        assertEquals(
                BatchDisposition.EXECUTED,
                toolBatchCoordinator.process(
                        resumed,
                        new ToolContext(resumed, workspace),
                        restored,
                        catalog,
                        restored.pendingToolCalls()));
        assertEquals(0, ticket.callCount());

        List<MessageEntity> toolMessages =
                messageRepository.findByTaskIdOrderByIdAsc(pending.task().getId()).stream()
                        .filter(message -> "tool".equals(message.getRole()))
                        .toList();
        assertEquals(1, toolMessages.size());
        assertEquals(pending.call().id(), toolMessages.getFirst().getToolCallId());
        assertEquals(
                "Approval rejected for create_ticket; no remote action occurred.",
                toolMessages.getFirst().getContent());
        assertTrue(toolMessages.getFirst().getContent().length() <= 512);
        assertFalse(toolMessages.getFirst().getContent().contains(rawSentinel));

        ApprovalDecisionTransaction.DecisionOutcome replay =
                decisionTransaction.decide(
                        pending.task().getId(),
                        pending.call().id(),
                        new ApprovalDecisionRequest(
                                ApprovalDecision.REJECT, "insufficient evidence"));
        assertFalse(replay.shouldResume());
        assertEquals(1,
                messageRepository.findByTaskIdOrderByIdAsc(pending.task().getId()).stream()
                        .filter(message -> "tool".equals(message.getRole()))
                        .count());
    }

    @Test
    void wrongTaskIsNotFoundAndTerminalTaskDecisionConflicts() {
        PendingApproval pending = pendingApproval(
                "call-conflicts",
                "{\"title\":\"Database unavailable\",\"severity\":\"sev1\","
                        + "\"evidence\":\"pool exhausted\"}");
        TaskEntity other = stateStore.createTask("other task", "system");
        ApprovalDecisionRequest approve =
                new ApprovalDecisionRequest(ApprovalDecision.APPROVE, null);

        assertThrows(
                TaskNotFoundException.class,
                () -> decisionTransaction.decide(
                        other.getId(), pending.call().id(), approve));

        jdbc.update(
                "UPDATE task SET status = 'CANCELLED' WHERE id = ?",
                pending.task().getId());
        entityManager.clear();
        assertThrows(
                ApprovalConflictException.class,
                () -> decisionTransaction.decide(
                        pending.task().getId(), pending.call().id(), approve));
    }

    @Test
    void listProjectionBoundsFieldsAndMalformedStoredShapeFailsClosed() {
        String title = "T".repeat(300);
        String severity = "S".repeat(30);
        String evidence = "E".repeat(700);
        PendingApproval pending = pendingApproval(
                "call-projection",
                "{\"title\":\"" + title + "\",\"severity\":\"" + severity
                        + "\",\"evidence\":\"" + evidence + "\"}");

        ApprovalView view =
                decisionTransaction.list(pending.task().getId()).getFirst();

        assertEquals(255, view.title().length());
        assertEquals(title.substring(0, 255), view.title());
        assertEquals(16, view.severity().length());
        assertEquals(severity.substring(0, 16), view.severity());
        assertEquals(512, view.evidencePreview().length());
        assertTrue(view.evidencePreview().endsWith("…[truncated]"));

        jdbc.update("""
                UPDATE approval_request
                   SET arguments_snapshot =
                       '{"title":{"secret":"RAW_SHAPE_SENTINEL"},'
                       '"severity":"sev1","evidence":"hidden"}'
                 WHERE tool_call_id = ?
                """, pending.call().id());
        entityManager.clear();
        IllegalStateException malformed = assertThrows(
                IllegalStateException.class,
                () -> decisionTransaction.list(pending.task().getId()));
        assertEquals(
                "Stored approval payload has unexpected shape",
                malformed.getMessage());
        assertFalse(malformed.getMessage().contains("RAW_SHAPE_SENTINEL"));
    }

    @Test
    void astralUnicodeProjectionRespectsCharacterCapsWithoutSplittingSurrogates() {
        String title = "T".repeat(254) + "😀" + "tail";
        String severity = "S".repeat(15) + "🚨" + "tail";
        String evidence = "E".repeat(499) + "🔎" + "tail".repeat(20);
        PendingApproval pending = pendingApproval(
                "call-astral-projection",
                "{\"title\":\"" + title + "\",\"severity\":\"" + severity
                        + "\",\"evidence\":\"" + evidence + "\"}");

        ApprovalView view =
                decisionTransaction.list(pending.task().getId()).getFirst();

        assertEquals(255, codePointLength(view.title()));
        assertEquals(16, codePointLength(view.severity()));
        assertEquals(512, codePointLength(view.evidencePreview()));
        assertFalse(hasUnpairedSurrogate(view.title()));
        assertFalse(hasUnpairedSurrogate(view.severity()));
        assertFalse(hasUnpairedSurrogate(view.evidencePreview()));
        assertTrue(view.title().endsWith("😀"));
        assertTrue(view.severity().endsWith("🚨"));
        assertTrue(view.evidencePreview().endsWith("…[truncated]"));
    }

    @ParameterizedTest(
            name = "rejected call first={0}, approve decision first={1}")
    @CsvSource({
            "true, true",
            "true, false",
            "false, true",
            "false, false"
    })
    void approvedRejectedMixedBatchPersistsResultsInAssistantOrder(
            boolean rejectedFirst,
            boolean approveFirst,
            @TempDir Path workspace
    ) {
        RecordingTool ticket = new RecordingTool(
                "create_ticket",
                IdempotencyClass.IDEMPOTENT,
                ApprovalPolicy.REQUIRE_APPROVAL,
                "ticket-created");
        TaskToolCatalog catalog = catalog(ticket);
        TaskEntity task = stateStore.createTask("mixed decisions", "system");
        TaskRunToken initialToken =
                stateStore.claim(task.getId()).orElseThrow();
        ToolCall rejected = new ToolCall(
                "call-mixed-rejected-" + rejectedFirst,
                ticket.name(),
                "{\"title\":\"Reject\",\"severity\":\"sev2\","
                        + "\"evidence\":\"not enough\"}");
        ToolCall approved = new ToolCall(
                "call-mixed-approved-" + rejectedFirst,
                ticket.name(),
                "{\"title\":\"Approve\",\"severity\":\"sev1\","
                        + "\"evidence\":\"confirmed\"}");
        List<ToolCall> calls = rejectedFirst
                ? List.of(rejected, approved)
                : List.of(approved, rejected);
        stateStore.appendAssistant(initialToken, Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", calls.stream()
                        .map(ApprovalServiceIT::assistantToolCall)
                        .toList()));
        assertTrue(stateStore.prepareApprovalBarrier(
                initialToken, catalog, calls));

        ApprovalDecisionTransaction.DecisionOutcome first =
                approveFirst
                        ? decisionTransaction.decide(
                                task.getId(),
                                approved.id(),
                                new ApprovalDecisionRequest(
                                        ApprovalDecision.APPROVE,
                                        "confirmed"))
                        : decisionTransaction.decide(
                                task.getId(),
                                rejected.id(),
                                new ApprovalDecisionRequest(
                                        ApprovalDecision.REJECT,
                                        "not enough"));
        ApprovalDecisionTransaction.DecisionOutcome second =
                approveFirst
                        ? decisionTransaction.decide(
                                task.getId(),
                                rejected.id(),
                                new ApprovalDecisionRequest(
                                        ApprovalDecision.REJECT,
                                        "not enough"))
                        : decisionTransaction.decide(
                                task.getId(),
                                approved.id(),
                                new ApprovalDecisionRequest(
                                        ApprovalDecision.APPROVE,
                                        "confirmed"));
        assertFalse(first.shouldResume());
        assertTrue(second.shouldResume());

        TaskRunToken resumed = stateStore.claim(task.getId()).orElseThrow();
        DefaultToolBatchCoordinator coordinator =
                new DefaultToolBatchCoordinator(
                        stateStore,
                        toolExecutor,
                        org.mockito.Mockito.mock(StreamTransport.class),
                        new TaskControl(),
                        FaultInjector.none());
        Context restored = stateStore.loadContext(task.getId());
        BatchDisposition disposition = coordinator.process(
                resumed,
                new ToolContext(resumed, workspace),
                restored,
                catalog,
                restored.pendingToolCalls());

        entityManager.clear();
        assertEquals(BatchDisposition.EXECUTED, disposition);
        assertEquals(1, ticket.callCount());
        assertEquals(ToolCallStatus.REJECTED,
                stateStore.statusOf(task.getId(), rejected.id()));
        assertEquals(ToolCallStatus.DONE,
                stateStore.statusOf(task.getId(), approved.id()));
        List<MessageEntity> toolMessages =
                messageRepository.findByTaskIdOrderByIdAsc(task.getId()).stream()
                        .filter(message -> "tool".equals(message.getRole()))
                        .toList();
        assertEquals(
                calls.stream().map(ToolCall::id).toList(),
                toolMessages.stream()
                        .map(MessageEntity::getToolCallId)
                        .toList());
        assertEquals(
                rejectedFirst
                        ? List.of(
                                "Approval rejected for create_ticket; no remote action occurred.",
                                "ticket-created")
                        : List.of(
                                "ticket-created",
                                "Approval rejected for create_ticket; no remote action occurred."),
                toolMessages.stream().map(MessageEntity::getContent).toList());
    }

    @Test
    void waitingApprovalSurvivesClosedContextAndNewContextCompletesRecovery(
            @TempDir Path workspace
    ) {
        String taskId;
        String callId = "call-restart-approved";

        try (ConfigurableApplicationContext first =
                     restartContext("approval-restart-a", workspace)) {
            StateStore firstStore = first.getBean(StateStore.class);
            ToolCatalogResolver resolver =
                    first.getBean(ToolCatalogResolver.class);
            TaskProfileSnapshot snapshot = resolver.snapshot(
                    new AgentProfileDefinition(
                            "restart-approval",
                            "v1",
                            "system",
                            null,
                            null,
                            List.of("fake-ops"),
                            List.of("create_ticket")));
            TaskToolCatalog catalog = resolver.resolve(snapshot);
            TaskEntity task =
                    firstStore.createTask("restart approval", snapshot);
            taskId = task.getId();
            TaskRunToken token =
                    firstStore.claim(taskId).orElseThrow();
            ToolCall call = new ToolCall(
                    callId,
                    "create_ticket",
                    "{\"title\":\"Restart recovery\","
                            + "\"severity\":\"sev1\","
                            + "\"evidence\":\"durable approval row\"}");
            firstStore.appendAssistant(token, Map.of(
                    "role", "assistant",
                    "content", "",
                    "tool_calls", List.of(assistantToolCall(call))));

            assertTrue(firstStore.prepareApprovalBarrier(
                    token, catalog, List.of(call)));
            assertEquals(TaskStatus.WAITING_APPROVAL,
                    firstStore.getTask(taskId).getStatus());
            assertEquals(ApprovalStatus.PENDING,
                    first.getBean(ApprovalRequestRepository.class)
                            .findById(callId).orElseThrow().getStatus());
        }

        try (ConfigurableApplicationContext second =
                     restartContext("approval-restart-b", workspace)) {
            StateStore secondStore = second.getBean(StateStore.class);
            RestartLlmClient restartLlm =
                    second.getBean(RestartLlmClient.class);
            ScriptedLlmClient script = new ScriptedLlmClient(List.of(
                    ScriptedLlmClient.turn(
                            Set.of("create_ticket"),
                            messages -> messages.stream()
                                    .map(message -> message.get("role"))
                                    .toList()
                                    .equals(List.of(
                                            "system",
                                            "user",
                                            "assistant",
                                            "tool")),
                            Decision.finalAnswer(
                                    "restart complete",
                                    Map.of(
                                            "role",
                                            "assistant",
                                            "content",
                                            "restart complete")))));
            restartLlm.use(script);
            CountDownLatch completed = new CountDownLatch(1);
            StreamTransport transport =
                    second.getBean(StreamTransport.class);
            try (StreamTransport.Subscription ignored =
                         transport.subscribeWithReplay(
                                 taskId,
                                 "0",
                                 event -> {
                                     if (event.type()
                                             == TaskEvent.Type.COMPLETED) {
                                         completed.countDown();
                                     }
                                     return true;
                                 })) {
                ApprovalView decided =
                        second.getBean(ApprovalService.class).decide(
                                taskId,
                                callId,
                                new ApprovalDecisionRequest(
                                        ApprovalDecision.APPROVE,
                                        "approved after restart"));
                assertEquals(ApprovalStatus.APPROVED, decided.status());
                awaitCompletion(completed);
            }

            script.assertExhausted();
            assertEquals(TaskStatus.COMPLETED,
                    secondStore.getTask(taskId).getStatus());
            assertEquals("restart complete",
                    secondStore.getTask(taskId).getResult());
            assertEquals(ToolCallStatus.DONE,
                    secondStore.statusOf(taskId, callId));
            assertEquals(1,
                    second.getBean(RestartMcpGateway.class)
                            .callCount());
            assertEquals(callId,
                    second.getBean(RestartMcpGateway.class)
                            .lastArguments()
                            .get("idempotency_key"));
            assertEquals(
                    List.of(
                            "system",
                            "user",
                            "assistant",
                            "tool",
                            "assistant"),
                    second.getBean(MessageRepository.class)
                            .findByTaskIdOrderByIdAsc(taskId)
                            .stream()
                            .map(MessageEntity::getRole)
                            .toList());
        }
    }

    @ParameterizedTest(name = "read-only call first={0}")
    @ValueSource(booleans = {true, false})
    void waitingCancellationRejectsEveryBlockedCallInAssistantOrderWithoutExecutionOrResume(
            boolean readFirst
    ) {
        RecordingTool read = new RecordingTool(
                "query_metrics",
                IdempotencyClass.READ_ONLY,
                ApprovalPolicy.NONE,
                "must not execute");
        RecordingTool ticket = new RecordingTool(
                "create_ticket",
                IdempotencyClass.IDEMPOTENT,
                ApprovalPolicy.REQUIRE_APPROVAL,
                "must not execute");
        TaskToolCatalog catalog = catalog(read, ticket);
        TaskEntity task = stateStore.createTask("cancel blocked batch", "system");
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        ToolCall readCall = new ToolCall(
                "call-cancel-read-" + readFirst,
                read.name(),
                "{\"query\":\"RAW_READ_SENTINEL\"}");
        ToolCall firstTicket = new ToolCall(
                "call-cancel-ticket-a-" + readFirst,
                ticket.name(),
                "{\"title\":\"Database unavailable\",\"severity\":\"sev1\","
                        + "\"evidence\":\"RAW_TICKET_SENTINEL_A\"}");
        ToolCall secondTicket = new ToolCall(
                "call-cancel-ticket-b-" + readFirst,
                ticket.name(),
                "{\"title\":\"Cache unavailable\",\"severity\":\"sev2\","
                        + "\"evidence\":\"RAW_TICKET_SENTINEL_B\"}");
        List<ToolCall> calls = readFirst
                ? List.of(readCall, firstTicket, secondTicket)
                : List.of(firstTicket, secondTicket, readCall);
        stateStore.appendAssistant(token, Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", calls.stream()
                        .map(ApprovalServiceIT::assistantToolCall)
                        .toList()));
        assertTrue(stateStore.prepareApprovalBarrier(token, catalog, calls));

        assertTrue(approvalService.cancelWaiting(task.getId()));
        assertFalse(approvalService.cancelWaiting(task.getId()));

        entityManager.clear();
        assertEquals(TaskStatus.CANCELLED, stateStore.getTask(task.getId()).getStatus());
        List<ApprovalRequestEntity> approvals =
                approvalRepository
                        .findByTaskIdOrderByAssistantMessageSeqAscToolCallIdAsc(
                                task.getId());
        assertEquals(2, approvals.size());
        assertTrue(approvals.stream().allMatch(
                approval -> approval.getStatus() == ApprovalStatus.REJECTED));
        assertTrue(approvals.stream().allMatch(
                approval -> "task-cancelled".equals(
                        approval.getDecisionReason())));
        assertEquals(
                List.of(
                        ToolCallStatus.REJECTED,
                        ToolCallStatus.REJECTED,
                        ToolCallStatus.REJECTED),
                calls.stream()
                        .map(call -> stateStore.statusOf(task.getId(), call.id()))
                        .toList());

        List<MessageEntity> syntheticResults =
                messageRepository.findByTaskIdOrderByIdAsc(task.getId()).stream()
                        .filter(message -> "tool".equals(message.getRole()))
                        .toList();
        assertEquals(
                calls.stream().map(ToolCall::id).toList(),
                syntheticResults.stream()
                        .map(MessageEntity::getToolCallId)
                        .toList());
        assertEquals(calls.size(), syntheticResults.size());
        assertTrue(syntheticResults.stream().allMatch(
                message -> message.getContent().length() <= 512));
        assertTrue(syntheticResults.stream().allMatch(
                message -> message.getContent().contains(
                        "no remote action occurred")));
        assertTrue(syntheticResults.stream().noneMatch(
                message -> message.getContent().contains("RAW_")));

        assertEquals(0, read.callCount());
        assertEquals(0, ticket.callCount());
        verify(runner, never()).resumeAsync(task.getId());
        assertThrows(
                ApprovalConflictException.class,
                () -> decisionTransaction.decide(
                        task.getId(),
                        firstTicket.id(),
                        new ApprovalDecisionRequest(
                                ApprovalDecision.APPROVE, "too late")));
        assertEquals(calls.size(),
                messageRepository.findByTaskIdOrderByIdAsc(task.getId()).stream()
                        .filter(message -> "tool".equals(message.getRole()))
                        .count());
    }

    @Test
    void waitingCancellationAfterPartialRejectionPersistsEveryResultInAssistantOrder() {
        RecordingTool read = new RecordingTool(
                "query_metrics",
                IdempotencyClass.READ_ONLY,
                ApprovalPolicy.NONE,
                "must not execute");
        RecordingTool ticket = new RecordingTool(
                "create_ticket",
                IdempotencyClass.IDEMPOTENT,
                ApprovalPolicy.REQUIRE_APPROVAL,
                "must not execute");
        TaskToolCatalog catalog = catalog(read, ticket);
        TaskEntity task =
                stateStore.createTask("cancel partially decided batch", "system");
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        ToolCall pendingFirst = new ToolCall(
                "call-partial-pending",
                ticket.name(),
                "{\"title\":\"Pending\",\"severity\":\"sev1\","
                        + "\"evidence\":\"pending evidence\"}");
        ToolCall rejectedSecond = new ToolCall(
                "call-partial-rejected",
                ticket.name(),
                "{\"title\":\"Rejected\",\"severity\":\"sev2\","
                        + "\"evidence\":\"rejected evidence\"}");
        ToolCall readThird = new ToolCall(
                "call-partial-read",
                read.name(),
                "{\"query\":\"must not run\"}");
        List<ToolCall> calls =
                List.of(pendingFirst, rejectedSecond, readThird);
        stateStore.appendAssistant(token, Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", calls.stream()
                        .map(ApprovalServiceIT::assistantToolCall)
                        .toList()));
        assertTrue(stateStore.prepareApprovalBarrier(token, catalog, calls));

        ApprovalDecisionTransaction.DecisionOutcome rejected =
                decisionTransaction.decide(
                        task.getId(),
                        rejectedSecond.id(),
                        new ApprovalDecisionRequest(
                                ApprovalDecision.REJECT,
                                "insufficient evidence"));
        assertFalse(rejected.shouldResume());
        assertTrue(approvalService.cancelWaiting(task.getId()));

        entityManager.clear();
        assertEquals(TaskStatus.CANCELLED,
                stateStore.getTask(task.getId()).getStatus());
        assertEquals(
                calls.stream().map(ToolCall::id).toList(),
                messageRepository.findByTaskIdOrderByIdAsc(task.getId()).stream()
                        .filter(message -> "tool".equals(message.getRole()))
                        .map(MessageEntity::getToolCallId)
                        .toList());
        assertEquals(
                List.of(
                        "Task cancelled before create_ticket; no remote action occurred.",
                        "Approval rejected for create_ticket; no remote action occurred.",
                        "Task cancelled before query_metrics; no remote action occurred."),
                messageRepository.findByTaskIdOrderByIdAsc(task.getId()).stream()
                        .filter(message -> "tool".equals(message.getRole()))
                        .map(MessageEntity::getContent)
                        .toList());
        assertEquals(0, ticket.callCount());
        assertEquals(0, read.callCount());
        verify(runner, never()).resumeAsync(task.getId());
    }

    @ParameterizedTest(name = "cancel signal is local={0}")
    @ValueSource(booleans = {true, false})
    void cancelSignalBeforeApprovalTransitionCannotStrandWaitingTask(
            boolean localCancel,
            @TempDir Path workspace
    ) {
        RecordingTool ticket = new RecordingTool(
                "create_ticket",
                IdempotencyClass.IDEMPOTENT,
                ApprovalPolicy.REQUIRE_APPROVAL,
                "must not execute");
        TaskToolCatalog catalog = catalog(ticket);
        TaskEntity task =
                stateStore.createTask("cancel during approval transition", "system");
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        ToolCall call = new ToolCall(
                "call-transition-" + localCancel,
                ticket.name(),
                "{\"title\":\"Transition\",\"severity\":\"sev1\","
                        + "\"evidence\":\"cancel race\"}");
        stateStore.appendAssistant(token, Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(assistantToolCall(call))));

        if (localCancel) {
            taskControl.begin(token);
            assertTrue(taskControl.requestCancel(task.getId(), false));
        } else {
            assertTrue(stateStore.requestControl(task.getId(), "CANCEL"));
        }
        try {
            assertEquals(
                    BatchDisposition.WAITING_APPROVAL,
                    toolBatchCoordinator.process(
                            token,
                            new ToolContext(token, workspace),
                            stateStore.loadContext(task.getId()),
                            catalog,
                            List.of(call)));
        } finally {
            if (localCancel) {
                taskControl.end(task.getId());
            }
        }

        entityManager.clear();
        assertEquals(TaskStatus.CANCELLED,
                stateStore.getTask(task.getId()).getStatus());
        ApprovalRequestEntity approval =
                approvalRepository.findById(call.id()).orElseThrow();
        assertEquals(ApprovalStatus.REJECTED, approval.getStatus());
        assertEquals("task-cancelled", approval.getDecisionReason());
        assertEquals(ToolCallStatus.REJECTED,
                stateStore.statusOf(task.getId(), call.id()));
        assertEquals(
                List.of(call.id()),
                messageRepository.findByTaskIdOrderByIdAsc(task.getId()).stream()
                        .filter(message -> "tool".equals(message.getRole()))
                        .map(MessageEntity::getToolCallId)
                        .toList());
        assertEquals(0, ticket.callCount());
    }

    private static Map<String, Object> assistantToolCall(ToolCall call) {
        return Map.of(
                "id", call.id(),
                "type", "function",
                "function", Map.of(
                        "name", call.name(),
                        "arguments", call.arguments()));
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(
                                value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private ConfigurableApplicationContext restartContext(
            String workerId,
            Path workspace
    ) {
        return new SpringApplicationBuilder(
                ReAgentApplication.class,
                RestartTestBeans.class)
                .profiles("test")
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.main.allow-bean-definition-overriding=true",
                        "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                        "--spring.datasource.username=" + MYSQL.getUsername(),
                        "--spring.datasource.password=" + MYSQL.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port="
                                + REDIS.getMappedPort(6379),
                        "--reagent.streaming.transport=in-process",
                        "--reagent.recovery.enabled=false",
                        "--reagent.worker.id=" + workerId,
                        "--reagent.sandbox.workspace-root=" + workspace);
    }

    private static void awaitCompletion(CountDownLatch completed) {
        try {
            assertTrue(
                    completed.await(10, TimeUnit.SECONDS),
                    "restart recovery did not publish COMPLETED");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(
                    "interrupted while awaiting restart recovery",
                    exception);
        }
    }

    private PendingApproval pendingApproval(String callId, String arguments) {
        RecordingTool ticket = new RecordingTool(
                "create_ticket",
                IdempotencyClass.IDEMPOTENT,
                ApprovalPolicy.REQUIRE_APPROVAL,
                "must not execute");
        TaskToolCatalog catalog = catalog(ticket);
        TaskEntity task = stateStore.createTask("approve ticket", "system");
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        ToolCall call = new ToolCall(callId, ticket.name(), arguments);
        stateStore.appendAssistant(token, Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of(
                                "name", call.name(),
                                "arguments", call.arguments())))));
        assertTrue(stateStore.prepareApprovalBarrier(token, catalog, List.of(call)));
        return new PendingApproval(task, call);
    }

    private static TaskToolCatalog catalog(RecordingTool... tools) {
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(Arrays.asList(tools)),
                new SchemaHasher(new ObjectMapper()),
                new ToolProperties());
        TaskProfileSnapshot snapshot = resolver.snapshot(new AgentProfileDefinition(
                "test",
                "v1",
                "system",
                null,
                null,
                List.of(),
                Arrays.stream(tools).map(RecordingTool::name).toList()));
        return resolver.resolve(snapshot);
    }

    private record PendingApproval(TaskEntity task, ToolCall call) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RestartTestBeans {

        @Bean
        @Primary
        RestartLlmClient restartLlmClient() {
            return new RestartLlmClient();
        }

        @Bean
        @Primary
        RestartMcpGateway restartMcpGateway() {
            return new RestartMcpGateway();
        }
    }

    static final class RestartLlmClient implements LlmClient {

        private final AtomicReference<LlmClient> delegate =
                new AtomicReference<>();

        void use(LlmClient client) {
            delegate.set(client);
        }

        @Override
        public Decision chat(
                Context context,
                List<Map<String, Object>> toolSpecs
        ) {
            return current().chat(context, toolSpecs);
        }

        @Override
        public Decision chatStream(
                Context context,
                List<Map<String, Object>> toolSpecs,
                Consumer<String> onToken
        ) {
            return current().chatStream(context, toolSpecs, onToken);
        }

        private LlmClient current() {
            LlmClient selected = delegate.get();
            if (selected == null) {
                throw new AssertionError(
                        "Restart test LLM was not configured");
            }
            return selected;
        }
    }

    static final class RestartMcpGateway implements McpGateway {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile Map<String, Object> lastArguments = Map.of();

        @Override
        public List<McpRemoteTool> discover(String serverId) {
            return List.of(new McpRemoteTool(
                    "fake-ops",
                    "create_ticket",
                    "Deterministic restart ticket",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "title", Map.of("type", "string"),
                                    "severity", Map.of("type", "string"),
                                    "evidence", Map.of("type", "string"),
                                    "idempotency_key",
                                    Map.of("type", "string")),
                            "required", List.of(
                                    "title",
                                    "severity",
                                    "evidence",
                                    "idempotency_key"))));
        }

        @Override
        public McpCallResult call(
                String serverId,
                String toolName,
                Map<String, Object> arguments
        ) {
            calls.incrementAndGet();
            lastArguments = Map.copyOf(arguments);
            return new McpCallResult("restart-ticket-created", false);
        }

        int callCount() {
            return calls.get();
        }

        Map<String, Object> lastArguments() {
            return lastArguments;
        }

        @Override
        public void close() {
        }
    }
}
