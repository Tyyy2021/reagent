package com.reagent.incident;

import com.reagent.core.FaultContext;
import com.reagent.core.FaultPoint;
import com.reagent.persist.ToolCallStatus;
import com.reagent.testsupport.IncidentScenarioFixture;
import com.reagent.testsupport.LatchingFaultInjector;
import com.reagent.testsupport.PythonCapabilitiesContainer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentCrashMatrixIT extends IncidentScenarioFixture {

    private static final int TICKET_ASSISTANT_SEQUENCE = 7;

    @ParameterizedTest(name = "{0}")
    @EnumSource(FaultPoint.class)
    void recoversFromEveryCommittedIncidentBoundary(FaultPoint point) {
        assertExactIncidentCatalog();
        ScenarioHandle scenario;
        ApprovalSnapshot approval = null;
        CompletableFuture<Void> failedApprovalRequest = null;
        LatchingFaultInjector.Arm arm;

        if (point == FaultPoint.AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL) {
            arm = faults.arm(
                    point,
                    context -> context.batchSequence()
                            .filter(sequence -> sequence == TICKET_ASSISTANT_SEQUENCE)
                            .isPresent());
            scenario = submitIncident("TASK11-CRASH-" + point.name());
        } else {
            scenario = submitIncident("TASK11-CRASH-" + point.name());
            approval = awaitPendingApproval(scenario);
            arm = faults.arm(
                    point,
                    context -> context.toolCallId()
                            .filter(scenario.script().ticketCallId()::equals)
                            .isPresent());
            if (point == FaultPoint.AFTER_APPROVAL_DECIDED_BEFORE_RESUME) {
                ApprovalSnapshot pending = approval;
                failedApprovalRequest = CompletableFuture.runAsync(() -> post(
                        "/api/tasks/" + scenario.taskId()
                                + "/approvals/" + pending.toolCallId() + "/decision",
                        java.util.Map.of(
                                "decision", "APPROVE",
                                "reason", "Task 11 deterministic crash matrix"),
                        500));
            } else {
                decide(scenario, approval, "APPROVE");
            }
        }

        FaultContext reached = arm.awaitReached(SCENARIO_TIMEOUT);
        try {
            assertBoundary(point, scenario, reached);
        } finally {
            arm.releaseCrash();
        }
        if (failedApprovalRequest != null) {
            failedApprovalRequest.join();
        }
        awaitDriverStopped(scenario.taskId());

        runner.recover(scenario.taskId());
        if (point == FaultPoint.AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL) {
            ApprovalSnapshot recovered = awaitPendingApproval(scenario);
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM approval_request WHERE task_id = ?",
                            scenario.taskId()));
            decide(scenario, recovered, "APPROVE");
        }

        awaitCompleted(scenario);
        assertRecovery(point, scenario);
    }

    private void assertBoundary(
            FaultPoint point,
            ScenarioHandle scenario,
            FaultContext reached
    ) {
        String taskId = scenario.taskId();
        String ticketCallId = scenario.script().ticketCallId();
        assertEquals(taskId, reached.taskId());
        assertEquals("incident-worker-a", reached.workerId());
        assertTrue(reached.leaseEpoch() > 0);

        switch (point) {
            case AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL -> {
                assertEquals(Optional.empty(), reached.toolCallId());
                assertEquals(
                        Optional.of(TICKET_ASSISTANT_SEQUENCE),
                        reached.batchSequence());
                assertEquals("RUNNING", taskStatus(taskId));
                assertEquals(
                        ToolCallStatus.PENDING.name(),
                        toolStatus(ticketCallId));
                assertEquals(
                        1,
                        count(
                                """
                                SELECT COUNT(*) FROM message
                                 WHERE task_id = ? AND seq = 7
                                   AND role = 'assistant'
                                   AND tool_calls_json IS NOT NULL
                                """,
                                taskId));
                assertEquals(
                        0,
                        count(
                                "SELECT COUNT(*) FROM approval_request WHERE task_id = ?",
                                taskId));
            }
            case AFTER_APPROVAL_DECIDED_BEFORE_RESUME -> {
                assertEquals(Optional.of(ticketCallId), reached.toolCallId());
                assertEquals(
                        Optional.of(TICKET_ASSISTANT_SEQUENCE),
                        reached.batchSequence());
                assertEquals("RUNNING", taskStatus(taskId));
                assertNull(taskOwner(taskId));
                assertEquals("APPROVED", approvalStatus(ticketCallId));
                assertEquals(
                        ToolCallStatus.PENDING.name(),
                        toolStatus(ticketCallId));
                assertEquals(0, acceptance(scenario).createTicketAttempts());
            }
            case AFTER_TOOL_MARKED_IN_PROGRESS -> {
                assertToolBoundaryContext(reached, ticketCallId);
                assertEquals(
                        ToolCallStatus.IN_PROGRESS.name(),
                        toolStatus(ticketCallId));
                assertEquals(1, attemptCount(ticketCallId));
                assertEquals(0, acceptance(scenario).createTicketAttempts());
            }
            case AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT -> {
                assertToolBoundaryContext(reached, ticketCallId);
                assertEquals(
                        ToolCallStatus.IN_PROGRESS.name(),
                        toolStatus(ticketCallId));
                PythonCapabilitiesContainer.AcceptanceSnapshot acceptance =
                        acceptance(scenario);
                assertEquals(1, acceptance.createTicketAttempts());
                assertEquals(1, acceptance.uniqueTicketCount());
            }
            case AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER -> {
                assertToolBoundaryContext(reached, ticketCallId);
                assertEquals(
                        ToolCallStatus.DONE.name(),
                        toolStatus(ticketCallId));
                assertEquals(
                        1,
                        count(
                                """
                                SELECT COUNT(*) FROM message
                                 WHERE task_id = ? AND role = 'tool'
                                   AND tool_call_id = ?
                                """,
                                taskId,
                                ticketCallId));
                assertEquals(1, acceptance(scenario).createTicketAttempts());
            }
        }
    }

    private void assertRecovery(FaultPoint point, ScenarioHandle scenario) {
        String ticketCallId = scenario.script().ticketCallId();
        PythonCapabilitiesContainer.AcceptanceSnapshot acceptance =
                acceptance(scenario);
        assertEquals(1, acceptance.uniqueTicketCount());
        assertEquals(scenario.script().ticketId(), acceptance.ticketId());
        assertEquals(ToolCallStatus.DONE.name(), toolStatus(ticketCallId));
        assertEquals(
                point == FaultPoint.AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT
                        ? 2
                        : 1,
                acceptance.createTicketAttempts());
        assertEquals(
                point == FaultPoint.AFTER_TOOL_MARKED_IN_PROGRESS
                                || point
                                == FaultPoint.AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT
                        ? 2
                        : 1,
                attemptCount(ticketCallId));
        assertEquals(
                1,
                count(
                        """
                        SELECT COUNT(*) FROM message
                         WHERE task_id = ? AND role = 'tool'
                           AND tool_call_id = ?
                        """,
                        scenario.taskId(),
                        ticketCallId));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM approval_request WHERE task_id = ?",
                        scenario.taskId()));
    }

    private static void assertToolBoundaryContext(
            FaultContext context,
            String ticketCallId
    ) {
        assertEquals(Optional.of(ticketCallId), context.toolCallId());
        assertEquals(Optional.empty(), context.batchSequence());
    }

    private String taskStatus(String taskId) {
        return jdbc.queryForObject(
                "SELECT status FROM task WHERE id = ?",
                String.class,
                taskId);
    }

    private String taskOwner(String taskId) {
        return jdbc.queryForObject(
                "SELECT owner_id FROM task WHERE id = ?",
                String.class,
                taskId);
    }

    private String toolStatus(String toolCallId) {
        return jdbc.queryForObject(
                "SELECT status FROM tool_call WHERE id = ?",
                String.class,
                toolCallId);
    }

    private int attemptCount(String toolCallId) {
        return jdbc.queryForObject(
                "SELECT attempt_count FROM tool_call WHERE id = ?",
                Integer.class,
                toolCallId);
    }

    private String approvalStatus(String toolCallId) {
        return jdbc.queryForObject(
                "SELECT status FROM approval_request WHERE tool_call_id = ?",
                String.class,
                toolCallId);
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }
}
