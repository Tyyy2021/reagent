package com.reagent.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.core.FaultPoint;
import com.reagent.testsupport.IncidentScenarioFixture;
import com.reagent.testsupport.LatchingFaultInjector;
import com.reagent.testsupport.PythonCapabilitiesContainer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentRejectIT extends IncidentScenarioFixture {

    @Test
    void rejectionCallsNoRemoteWriteAndStillCompletes() {
        assertExactIncidentCatalog();
        ScenarioHandle scenario = submitIncident("TASK11-REJECT-001");

        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        decide(scenario, approval, "REJECT");
        JsonNode completed = awaitCompleted(scenario);

        PythonCapabilitiesContainer.AcceptanceSnapshot acceptance =
                acceptance(scenario);
        assertEquals(0, acceptance.createTicketAttempts());
        assertEquals(0, acceptance.uniqueTicketCount());
        assertNull(acceptance.ticketId());
        assertTrue(completed.path("result").asText().contains("REJECTED"));
        assertTrue(completed.path("result").asText().contains(
                "no ticket was created"));
        assertReconstructableLedger(scenario.taskId());
    }

    @Test
    void oppositeApprovalDecisionReturnsConflictWithoutDuplicatingTheWrite() {
        ScenarioHandle scenario = submitIncident("TASK11-APPROVAL-CONFLICT-001");
        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        LatchingFaultInjector.Arm arm = faults.arm(
                FaultPoint.AFTER_APPROVAL_DECIDED_BEFORE_RESUME,
                context -> context.toolCallId()
                        .filter(approval.toolCallId()::equals)
                        .isPresent());
        CompletableFuture<JsonNode> approvedRequest =
                CompletableFuture.supplyAsync(() -> post(
                        "/api/tasks/" + scenario.taskId()
                                + "/approvals/" + approval.toolCallId()
                                + "/decision",
                        Map.of(
                                "decision", "APPROVE",
                                "reason", "Task 11 conflict probe"),
                        500));

        arm.awaitReached(SCENARIO_TIMEOUT);
        try {
            JsonNode conflict = post(
                    "/api/tasks/" + scenario.taskId()
                            + "/approvals/" + approval.toolCallId()
                            + "/decision",
                    Map.of(
                            "decision", "REJECT",
                            "reason", "Opposite decision must conflict"),
                    409);
            assertTrue(conflict.path("error").asText().contains(
                    "already decided differently"));

            JsonNode replay = post(
                    "/api/tasks/" + scenario.taskId()
                            + "/approvals/" + approval.toolCallId()
                            + "/decision",
                    Map.of(
                            "decision", "APPROVE",
                            "reason", "Same decision replay"),
                    200);
            assertEquals("APPROVED", replay.path("status").asText());
        } finally {
            arm.releaseCrash();
        }
        approvedRequest.join();
        awaitDriverStopped(scenario.taskId());

        runner.recover(scenario.taskId());
        awaitCompleted(scenario);

        PythonCapabilitiesContainer.AcceptanceSnapshot acceptance =
                acceptance(scenario);
        assertEquals(1, acceptance.createTicketAttempts());
        assertEquals(1, acceptance.uniqueTicketCount());
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM approval_request WHERE task_id = ?",
                        Integer.class,
                        scenario.taskId()));
        assertReconstructableLedger(scenario.taskId());
    }
}
