package com.reagent.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.core.FencedExecutionException;
import com.reagent.core.FaultPoint;
import com.reagent.core.TaskControl;
import com.reagent.core.TaskRunToken;
import com.reagent.core.ToolCall;
import com.reagent.persist.ToolCallStatus;
import com.reagent.testsupport.IncidentScenarioFixture;
import com.reagent.testsupport.LatchingFaultInjector;
import com.reagent.testsupport.PythonCapabilitiesContainer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentDangerousWindowIT extends IncidentScenarioFixture {

    private static final Pattern TICKET_ID = Pattern.compile("OPS-[0-9A-F]{12}");

    @Test
    void secondWorkerReplaysOneTicketAfterCommittedResponseIsLost() throws Exception {
        assertExactIncidentCatalog();
        ScenarioHandle scenario = submitIncident("TASK11-DANGEROUS-WINDOW-001");
        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        String ticketCallId = scenario.script().ticketCallId();
        PythonCapabilitiesContainer python = pythonCapabilities();
        python.armTicketAfterCommit(ticketCallId);
        LatchingFaultInjector.Arm workerBResult = faults.arm(
                FaultPoint.AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT,
                context -> "incident-worker-b".equals(context.workerId())
                        && context.toolCallId().filter(ticketCallId::equals).isPresent());

        CompletableFuture<String> workerBRecovery = null;
        boolean gateReleased = false;
        boolean workerBResultReleased = false;
        try {
            decide(scenario, approval, "APPROVE");
            python.awaitTicketAfterCommitBlocked(SCENARIO_TIMEOUT);
            PythonCapabilitiesContainer.AcceptanceSnapshot committed =
                    python.awaitCreateTicketAttempts(
                            ticketCallId,
                            1,
                            SCENARIO_TIMEOUT);
            assertEquals(1, committed.createTicketAttempts());
            assertEquals(1, committed.uniqueTicketCount());
            assertEquals("blocked", committed.faultGateState());
            assertEquals(
                    ToolCallStatus.IN_PROGRESS.name(),
                    toolStatus(ticketCallId));
            assertDriverRemainsRunning(
                    scenario.taskId(),
                    Duration.ofSeconds(4));

            TaskRunToken workerAToken = currentToken(scenario.taskId());
            assertEquals("incident-worker-a", workerAToken.workerId());
            IncidentWorkerRuntime workerB = worker(
                    "incident-worker-b",
                    Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2)),
                    3);
            workerBRecovery = CompletableFuture.supplyAsync(
                    () -> workerB.runner().recover(scenario.taskId()));

            long workerBEpoch = awaitOwner(
                    scenario.taskId(),
                    "incident-worker-b",
                    SCENARIO_TIMEOUT);
            assertTrue(workerBEpoch > workerAToken.leaseEpoch());
            assertTrue(taskControl.isRunning(scenario.taskId()));
            assertTrue(inFlight.isRunning(scenario.taskId()));
            taskControl.markFenced(scenario.taskId());
            assertEquals(
                    TaskControl.Signal.FENCED,
                    taskControl.signalOf(scenario.taskId()));

            ToolCall staleCall = new ToolCall(
                    ticketCallId,
                    "create_ticket",
                    jdbc.queryForObject(
                            "SELECT arguments FROM tool_call WHERE id = ?",
                            String.class,
                            ticketCallId));
            FencedExecutionException staleWrite = assertThrows(
                    FencedExecutionException.class,
                    () -> stateStore.recordToolResult(
                            workerAToken,
                            staleCall,
                            "{\"ticketId\":\"stale\"}"));
            assertEquals(workerAToken, staleWrite.token());
            assertEquals(workerBEpoch, staleWrite.actualEpoch());
            assertEquals("incident-worker-b", staleWrite.actualOwner());
            assertEquals("RUNNING", staleWrite.actualStatus().name());

            assertTrue(
                    taskControl.isRunning(scenario.taskId()),
                    "Worker A must still be live when the remote gate is released");
            python.releaseTicketAfterCommit();
            gateReleased = true;
            workerBResult.awaitReached(SCENARIO_TIMEOUT);
            PythonCapabilitiesContainer.AcceptanceSnapshot replayed =
                    python.awaitCreateTicketAttempts(
                            ticketCallId,
                            2,
                            SCENARIO_TIMEOUT);
            assertTrue(replayed.createTicketAttempts() >= 2);
            assertEquals(1, replayed.uniqueTicketCount());
            assertEquals(committed.ticketId(), replayed.ticketId());
            awaitDriverStopped(scenario.taskId());
            assertFalse(workerBRecovery.isDone());
            assertEquals(
                    "RUNNING",
                    jdbc.queryForObject(
                            "SELECT status FROM task WHERE id = ?",
                            String.class,
                            scenario.taskId()));
            assertEquals(
                    "incident-worker-b",
                    jdbc.queryForObject(
                            "SELECT owner_id FROM task WHERE id = ?",
                            String.class,
                            scenario.taskId()));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM event WHERE task_id = ? AND type = 'FAILED'",
                            scenario.taskId()));
            workerBResult.releaseNormally();
            workerBResultReleased = true;
            String recovered = workerBRecovery.get(
                    SCENARIO_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS);
            JsonNode completed = awaitCompleted(scenario);

            PythonCapabilitiesContainer.AcceptanceSnapshot acceptance =
                    acceptance(scenario);
            assertTrue(acceptance.createTicketAttempts() >= 2);
            assertEquals(1, acceptance.uniqueTicketCount());
            assertEquals(committed.ticketId(), acceptance.ticketId());
            assertEquals(scenario.script().finalAnswer(), recovered);
            assertEquals(
                    Set.of(acceptance.ticketId()),
                    ticketIds(completed.path("result").asText()));
            assertEquals(2, attemptCount(ticketCallId));
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
                    0,
                    count(
                            "SELECT COUNT(*) FROM event WHERE task_id = ? AND type = 'FAILED'",
                            scenario.taskId()));

        } finally {
            if (!workerBResultReleased) {
                workerBResult.releaseNormally();
            }
            if (!gateReleased) {
                try {
                    python.releaseTicketAfterCommit();
                } catch (AssertionError ignored) {
                    // The gate may already be disarmed by a completed response.
                }
            }
            if (workerBRecovery != null && !workerBRecovery.isDone()) {
                workerBRecovery.cancel(true);
            }
        }
        awaitDriverStopped(scenario.taskId());
    }

    private void assertDriverRemainsRunning(
            String taskId,
            Duration observationWindow
    ) {
        Instant deadline = Instant.now().plus(observationWindow);
        while (Instant.now().isBefore(deadline)) {
            assertTrue(
                    taskControl.isRunning(taskId) && inFlight.isRunning(taskId),
                    "Worker A must remain in-flight until the remote gate is released");
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Interrupted while observing Worker A",
                        exception);
            }
        }
    }

    private TaskRunToken currentToken(String taskId) {
        return new TaskRunToken(
                taskId,
                jdbc.queryForObject(
                        "SELECT owner_id FROM task WHERE id = ?",
                        String.class,
                        taskId),
                jdbc.queryForObject(
                        "SELECT lease_epoch FROM task WHERE id = ?",
                        Long.class,
                        taskId));
    }

    private long awaitOwner(
            String taskId,
            String expectedOwner,
            Duration timeout
    ) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            String owner = jdbc.queryForObject(
                    "SELECT owner_id FROM task WHERE id = ?",
                    String.class,
                    taskId);
            if (expectedOwner.equals(owner)) {
                return jdbc.queryForObject(
                        "SELECT lease_epoch FROM task WHERE id = ?",
                        Long.class,
                        taskId);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting Worker B", exception);
            }
        }
        throw new AssertionError(
                "Task was not claimed by " + expectedOwner + " within " + timeout);
    }

    private Set<String> ticketIds(String finalAnswer) {
        return TICKET_ID.matcher(finalAnswer)
                .results()
                .map(MatchResult::group)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }
}
