package com.reagent.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.approval.ApprovalRequestEntity;
import com.reagent.approval.ApprovalRequestRepository;
import com.reagent.approval.ApprovalStatus;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskRepository;
import com.reagent.persist.TaskStatus;
import com.reagent.persist.ToolCallEntity;
import com.reagent.persist.ToolCallRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AcceptanceServiceTest {

    @Test
    void authoritativeLedgersAndScopedPythonCountsProduceOnlyBoundedEvidence() {
        TaskRepository tasks = mock(TaskRepository.class);
        ToolCallRepository calls = mock(ToolCallRepository.class);
        ApprovalRequestRepository approvals = mock(ApprovalRequestRepository.class);
        PythonAcceptanceClient python = mock(PythonAcceptanceClient.class);
        TaskEntity task = mock(TaskEntity.class);
        when(task.getId()).thenReturn("task-acceptance");
        when(task.getProfileId()).thenReturn("incident-ops");
        when(task.getStatus()).thenReturn(TaskStatus.COMPLETED);
        when(task.getLeaseEpoch()).thenReturn(2L);
        when(tasks.findById("task-acceptance")).thenReturn(Optional.of(task));

        ToolCallEntity rag = done(
                "call-rag", "task-acceptance", "search_knowledge",
                """
                {"contractVersion":1,"indexVersion":"v1-safe","hits":[{
                  "chunkId":"runbooks/checkout-db-pool#mitigation#0123456789ab",
                  "source":"runbooks/checkout.md",
                  "excerpt":"SECRET_SENTINEL"}]}
                """);
        ToolCallEntity metrics = done(
                "call-metrics", "task-acceptance", "query_metrics",
                "{\"payload\":\"ARGUMENT_SENTINEL\"}");
        ToolCallEntity logs = done(
                "call-logs", "task-acceptance", "search_logs",
                "{\"entries\":[{\"line\":\"FULL_LOG_SENTINEL\"}]}");
        ToolCallEntity ticket = done(
                "call-ticket", "task-acceptance", "create_ticket",
                "{\"ticketId\":\"OPS-0123456789AB\",\"evidence\":\"SECRET_SENTINEL\"}");
        when(calls.findAll()).thenReturn(List.of(rag, metrics, logs, ticket));

        ApprovalRequestEntity approval = ApprovalRequestEntity.pending(
                "call-ticket", "task-acceptance", 8, "create_ticket",
                "{\"evidence\":\"ARGUMENT_SENTINEL\"}", Instant.EPOCH);
        approval.decide(ApprovalStatus.APPROVED, "safe", Instant.EPOCH.plusSeconds(1));
        when(approvals.findByTaskIdOrderByAssistantMessageSeqAscToolCallIdAsc(
                "task-acceptance")).thenReturn(List.of(approval));
        when(python.fetch("task-acceptance")).thenReturn(
                new PythonAcceptanceClient.PythonAcceptanceResponse(
                        1, "idempotency-key",
                        Map.of(
                                "query_metrics", 0,
                                "search_logs", 0,
                                "create_ticket", 2),
                        2, 1, List.of("OPS-0123456789AB"), "released"));

        AcceptanceEvidence evidence = new AcceptanceService(
                tasks, calls, approvals, python, new ObjectMapper())
                .evidence("task-acceptance");

        assertEquals(1, evidence.contractVersion());
        assertEquals(
                List.of("runbooks/checkout-db-pool#mitigation#0123456789ab"),
                evidence.citationIds());
        assertEquals(List.of("runbooks/checkout.md"), evidence.citationSources());
        assertEquals(List.of("query_metrics", "search_logs", "create_ticket"),
                evidence.mcpTools());
        assertEquals("APPROVED", evidence.approvalDecision());
        assertEquals(List.of(1L, 2L), evidence.workerEpochs());
        assertEquals("OPS-0123456789AB", evidence.ticketId());
        assertEquals(2, evidence.createTicketAttempts());
        assertEquals(1, evidence.uniqueTicketCount());
        assertTrue(evidence.passed());
        String safe = evidence.toString();
        assertFalse(safe.contains("SECRET_SENTINEL"));
        assertFalse(safe.contains("ARGUMENT_SENTINEL"));
        assertFalse(safe.contains("FULL_LOG_SENTINEL"));
    }

    @Test
    void completedNoHitBeforeTicketIsAValidZeroSideEffectOutcome() {
        TaskRepository tasks = mock(TaskRepository.class);
        ToolCallRepository calls = mock(ToolCallRepository.class);
        ApprovalRequestRepository approvals = mock(ApprovalRequestRepository.class);
        PythonAcceptanceClient python = mock(PythonAcceptanceClient.class);
        TaskEntity task = mock(TaskEntity.class);
        when(task.getId()).thenReturn("task-no-hit");
        when(task.getProfileId()).thenReturn("incident-ops");
        when(task.getStatus()).thenReturn(TaskStatus.COMPLETED);
        when(task.getLeaseEpoch()).thenReturn(1L);
        when(tasks.findById("task-no-hit")).thenReturn(Optional.of(task));
        when(calls.findAll()).thenReturn(List.of(done(
                "call-rag", "task-no-hit", "search_knowledge",
                "{\"contractVersion\":1,\"indexVersion\":\"v1\",\"hits\":[]}")));
        when(approvals.findByTaskIdOrderByAssistantMessageSeqAscToolCallIdAsc(
                "task-no-hit")).thenReturn(List.of());
        when(python.fetch("task-no-hit")).thenReturn(
                new PythonAcceptanceClient.PythonAcceptanceResponse(
                        1, "idempotency-key",
                        Map.of(
                                "query_metrics", 0,
                                "search_logs", 0,
                                "create_ticket", 0),
                        0, 0, List.of(), "not-invoked"));

        AcceptanceEvidence evidence = new AcceptanceService(
                tasks, calls, approvals, python, new ObjectMapper())
                .evidence("task-no-hit");

        assertTrue(evidence.passed());
        assertEquals("", evidence.ticketId());
        assertEquals(List.of(), evidence.citationIds());
    }

    private static ToolCallEntity done(
            String id,
            String taskId,
            String name,
            String result
    ) {
        ToolCallEntity call = new ToolCallEntity(id, taskId, name, "{}");
        call.markDone(result);
        return call;
    }
}
