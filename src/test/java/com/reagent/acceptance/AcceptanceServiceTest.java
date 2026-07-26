package com.reagent.acceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.approval.ApprovalRequestEntity;
import com.reagent.approval.ApprovalRequestRepository;
import com.reagent.approval.ApprovalStatus;
import com.reagent.persist.EventRepository;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskRepository;
import com.reagent.persist.TaskStatus;
import com.reagent.persist.ToolCallEntity;
import com.reagent.persist.ToolCallRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Arrays;
import java.util.stream.LongStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AcceptanceServiceTest {

    @Test
    void authoritativeLedgersAndScopedPythonCountsProduceOnlyBoundedEvidence() {
        TaskRepository tasks = mock(TaskRepository.class);
        ToolCallRepository calls = mock(ToolCallRepository.class);
        ApprovalRequestRepository approvals = mock(ApprovalRequestRepository.class);
        EventRepository events = mock(EventRepository.class);
        PythonAcceptanceClient python = mock(PythonAcceptanceClient.class);
        TaskEntity task = mock(TaskEntity.class);
        when(task.getId()).thenReturn("task-acceptance");
        when(task.getProfileId()).thenReturn("incident-ops");
        when(task.getStatus()).thenReturn(TaskStatus.COMPLETED);
        when(task.getLeaseEpoch()).thenReturn(2L);
        when(tasks.findById("task-acceptance")).thenReturn(Optional.of(task));
        when(events.findOrderedDistinctEpochValues(
                org.mockito.ArgumentMatchers.eq("task-acceptance"),
                org.mockito.ArgumentMatchers.eq("TASK_STARTED"),
                any(Pageable.class))).thenReturn(List.of("2", "7"));

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
                tasks, calls, approvals, events, python, new ObjectMapper())
                .evidence("task-acceptance");

        assertEquals(1, evidence.contractVersion());
        assertEquals(
                List.of("runbooks/checkout-db-pool#mitigation#0123456789ab"),
                evidence.citationIds());
        assertEquals(List.of("runbooks/checkout.md"), evidence.citationSources());
        assertEquals(List.of("query_metrics", "search_logs", "create_ticket"),
                evidence.mcpTools());
        assertEquals("APPROVED", evidence.approvalDecision());
        assertEquals(List.of(2L, 7L), evidence.workerEpochs());
        assertEquals("OPS-0123456789AB", evidence.ticketId());
        assertEquals(2, evidence.createTicketAttempts());
        assertEquals(1, evidence.uniqueTicketCount());
        assertTrue(evidence.passed());
        String safe = evidence.toString();
        assertFalse(safe.contains("SECRET_SENTINEL"));
        assertFalse(safe.contains("ARGUMENT_SENTINEL"));
        assertFalse(safe.contains("FULL_LOG_SENTINEL"));
        verify(events).findOrderedDistinctEpochValues(
                org.mockito.ArgumentMatchers.eq("task-acceptance"),
                org.mockito.ArgumentMatchers.eq("TASK_STARTED"),
                org.mockito.ArgumentMatchers.argThat(pageable ->
                        pageable.getPageNumber() == 0
                                && pageable.getPageSize() == 17));
    }

    @Test
    void completedNoHitBeforeTicketIsAValidZeroSideEffectOutcome() {
        TaskRepository tasks = mock(TaskRepository.class);
        ToolCallRepository calls = mock(ToolCallRepository.class);
        ApprovalRequestRepository approvals = mock(ApprovalRequestRepository.class);
        EventRepository events = mock(EventRepository.class);
        PythonAcceptanceClient python = mock(PythonAcceptanceClient.class);
        TaskEntity task = mock(TaskEntity.class);
        when(task.getId()).thenReturn("task-no-hit");
        when(task.getProfileId()).thenReturn("incident-ops");
        when(task.getStatus()).thenReturn(TaskStatus.COMPLETED);
        when(task.getLeaseEpoch()).thenReturn(1L);
        when(tasks.findById("task-no-hit")).thenReturn(Optional.of(task));
        when(events.findOrderedDistinctEpochValues(
                org.mockito.ArgumentMatchers.eq("task-no-hit"),
                org.mockito.ArgumentMatchers.eq("TASK_STARTED"),
                any(Pageable.class))).thenReturn(List.of("11"));
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
                tasks, calls, approvals, events, python, new ObjectMapper())
                .evidence("task-no-hit");

        assertTrue(evidence.passed());
        assertEquals("", evidence.ticketId());
        assertEquals(List.of(), evidence.citationIds());
        assertEquals(List.of(11L), evidence.workerEpochs());
    }

    @Test
    void zeroTaskStartedRowsReturnEmptyEpochEvidenceThatCannotPass() {
        AcceptanceEvidence evidence = noSideEffectService(List.of())
                .evidence("task-invalid-epochs");

        assertEquals(List.of(), evidence.workerEpochs());
        assertFalse(evidence.passed());
    }

    @Test
    void existingTaskStartedRowWithMissingEpochFailsClosed() {
        AcceptanceService service = noSideEffectService(
                List.of("__INVALID_LEASE_EPOCH__"));

        assertThrows(
                IllegalStateException.class,
                () -> service.evidence("task-invalid-epochs"));
    }

    @Test
    void malformedNonpositiveAndOverflowEpochEvidenceIsRejected() {
        List<List<String>> invalid = List.of(
                Arrays.asList((String) null),
                List.of(""),
                List.of("0"),
                List.of("-1"),
                List.of("not-an-epoch"),
                List.of("2", "__INVALID_LEASE_EPOCH__"),
                List.of("9223372036854775808"),
                LongStream.rangeClosed(1, 17).mapToObj(Long::toString).toList());

        for (List<String> epochRows : invalid) {
            AcceptanceService service = noSideEffectService(epochRows);

            assertThrows(
                    IllegalStateException.class,
                    () -> service.evidence("task-invalid-epochs"),
                    epochRows.toString());
        }
    }

    @Test
    void urlProtocolRelativeAndAbsolutePathEvidenceIsRejectedWithoutEcho() {
        for (String unsafe : List.of(
                "https://SECRET_SENTINEL.invalid/runbook",
                "//SECRET_SENTINEL/runbook",
                "/private/SECRET_SENTINEL/runbook")) {
            for (boolean unsafeChunkId : List.of(true, false)) {
                AcceptanceService service = ledgerService(
                        "incident-ops",
                        unsafeChunkId ? unsafe : "chunk-safe",
                        unsafeChunkId ? "runbooks/safe.md" : unsafe);

                IllegalStateException failure = assertThrows(
                        IllegalStateException.class,
                        () -> service.evidence("task-ledger-path"));

                assertEquals("Acceptance ledger is invalid", failure.getMessage());
                assertFalse(failure.toString().contains(unsafe));
                assertFalse(failure.toString().contains("SECRET_SENTINEL"));
            }
        }
    }

    @Test
    void profileRejectsUrlAndOverlongValuesWithoutEcho() {
        for (String unsafe : List.of(
                "https://PROFILE_SECRET_SENTINEL.invalid/value",
                "p".repeat(257))) {
            AcceptanceService service = ledgerService(
                    unsafe,
                    "runbooks/file.md#section#checksum",
                    "runbooks/file.md#section#checksum");

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> service.evidence("task-ledger-path"));

            assertEquals("Acceptance ledger is invalid", failure.getMessage());
            assertFalse(failure.toString().contains(unsafe));
            assertFalse(failure.toString().contains("PROFILE_SECRET_SENTINEL"));
        }
    }

    @Test
    void relativeRunbookSectionAndChecksumShapeRemainsValid() {
        AcceptanceEvidence evidence = ledgerService(
                "incident-ops",
                "runbooks/file.md#section#checksum",
                "runbooks/file.md#section#checksum")
                .evidence("task-ledger-path");

        assertEquals(
                List.of("runbooks/file.md#section#checksum"),
                evidence.citationIds());
        assertEquals(
                List.of("runbooks/file.md#section#checksum"),
                evidence.citationSources());
        assertTrue(evidence.passed());
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

    private static AcceptanceService noSideEffectService(List<String> epochRows) {
        TaskRepository tasks = mock(TaskRepository.class);
        ToolCallRepository calls = mock(ToolCallRepository.class);
        ApprovalRequestRepository approvals = mock(ApprovalRequestRepository.class);
        EventRepository events = mock(EventRepository.class);
        PythonAcceptanceClient python = mock(PythonAcceptanceClient.class);
        TaskEntity task = mock(TaskEntity.class);
        when(task.getId()).thenReturn("task-invalid-epochs");
        when(task.getProfileId()).thenReturn("incident-ops");
        when(task.getStatus()).thenReturn(TaskStatus.COMPLETED);
        when(tasks.findById("task-invalid-epochs")).thenReturn(Optional.of(task));
        when(calls.findAll()).thenReturn(List.of());
        when(approvals.findByTaskIdOrderByAssistantMessageSeqAscToolCallIdAsc(
                "task-invalid-epochs")).thenReturn(List.of());
        when(events.findOrderedDistinctEpochValues(
                org.mockito.ArgumentMatchers.eq("task-invalid-epochs"),
                org.mockito.ArgumentMatchers.eq("TASK_STARTED"),
                any(Pageable.class))).thenReturn(epochRows);
        when(python.fetch("task-invalid-epochs")).thenReturn(
                new PythonAcceptanceClient.PythonAcceptanceResponse(
                        1, "idempotency-key",
                        Map.of(
                                "query_metrics", 0,
                                "search_logs", 0,
                                "create_ticket", 0),
                        0, 0, List.of(), "not-invoked"));
        return new AcceptanceService(
                tasks, calls, approvals, events, python, new ObjectMapper());
    }

    private static AcceptanceService ledgerService(
            String profile,
            String chunkId,
            String source
    ) {
        TaskRepository tasks = mock(TaskRepository.class);
        ToolCallRepository calls = mock(ToolCallRepository.class);
        ApprovalRequestRepository approvals = mock(ApprovalRequestRepository.class);
        EventRepository events = mock(EventRepository.class);
        PythonAcceptanceClient python = mock(PythonAcceptanceClient.class);
        TaskEntity task = mock(TaskEntity.class);
        when(task.getId()).thenReturn("task-ledger-path");
        when(task.getProfileId()).thenReturn(profile);
        when(task.getStatus()).thenReturn(TaskStatus.COMPLETED);
        when(tasks.findById("task-ledger-path")).thenReturn(Optional.of(task));
        when(calls.findAll()).thenReturn(List.of(done(
                "call-rag",
                "task-ledger-path",
                "search_knowledge",
                """
                {"hits":[{"chunkId":"%s","source":"%s"}]}
                """.formatted(chunkId, source))));
        when(approvals.findByTaskIdOrderByAssistantMessageSeqAscToolCallIdAsc(
                "task-ledger-path")).thenReturn(List.of());
        when(events.findOrderedDistinctEpochValues(
                org.mockito.ArgumentMatchers.eq("task-ledger-path"),
                org.mockito.ArgumentMatchers.eq("TASK_STARTED"),
                any(Pageable.class))).thenReturn(List.of("1"));
        when(python.fetch("task-ledger-path")).thenReturn(
                new PythonAcceptanceClient.PythonAcceptanceResponse(
                        1, "idempotency-key",
                        Map.of(
                                "query_metrics", 0,
                                "search_logs", 0,
                                "create_ticket", 0),
                        0, 0, List.of(), "not-invoked"));
        return new AcceptanceService(
                tasks, calls, approvals, events, python, new ObjectMapper());
    }
}
