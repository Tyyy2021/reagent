package com.reagent.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.core.Context;
import com.reagent.core.Decision;
import com.reagent.core.FaultPoint;
import com.reagent.core.InjectedWorkerCrashException;
import com.reagent.core.TaskRunToken;
import com.reagent.core.ToolCall;
import com.reagent.llm.LlmClient;
import com.reagent.persist.TaskEntity;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.ToolSnapshot;
import com.reagent.testsupport.IncidentScenarioFixture;
import com.reagent.testsupport.LatchingFaultInjector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentAcceptanceIT extends IncidentScenarioFixture {

    @Autowired
    private AcceptanceService acceptanceService;

    @BeforeAll
    static void resetReports() {
        AcceptanceReportWriter.reset();
    }

    @Test
    void happy() {
        long started = System.nanoTime();
        ScenarioHandle scenario = submitIncident("TASK12-HAPPY-SECRET_SENTINEL");
        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        decide(scenario, approval, "APPROVE");
        awaitCompleted(scenario);

        AcceptanceEvidence evidence = acceptanceService.evidence(scenario.taskId());
        assertTrue(evidence.passed());
        record("happy", started, evidence);
    }

    @Test
    void reject() {
        long started = System.nanoTime();
        ScenarioHandle scenario = submitIncident("TASK12-REJECT-ARGUMENT_SENTINEL");
        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        decide(scenario, approval, "REJECT");
        awaitCompleted(scenario);

        AcceptanceEvidence evidence = acceptanceService.evidence(scenario.taskId());
        assertTrue(evidence.passed());
        record("reject", started, evidence);
    }

    @Test
    void dangerousCrash() {
        long started = System.nanoTime();
        ScenarioHandle scenario = submitIncident("TASK12-CRASH-FULL_LOG_SENTINEL");
        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        LatchingFaultInjector.Arm arm = faults.arm(
                FaultPoint.AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT,
                context -> context.toolCallId()
                        .filter(scenario.script().ticketCallId()::equals)
                        .isPresent());
        decide(scenario, approval, "APPROVE");
        arm.awaitReached(SCENARIO_TIMEOUT);
        arm.releaseCrash();
        awaitDriverStopped(scenario.taskId());
        runner.recover(scenario.taskId());
        awaitCompleted(scenario);

        AcceptanceEvidence evidence = acceptanceService.evidence(scenario.taskId());
        assertTrue(evidence.passed());
        assertTrue(evidence.createTicketAttempts() >= 2);
        record("dangerous-crash", started, evidence);
    }

    @Test
    void noHit() {
        long started = System.nanoTime();
        NoHitLlm llm = new NoHitLlm();
        String taskId = submitIncident("TASK12-NO-HIT-SECRET_SENTINEL", llm);
        awaitTaskStatus(taskId, "COMPLETED", SCENARIO_TIMEOUT);
        llm.assertExhausted();

        AcceptanceEvidence evidence = acceptanceService.evidence(taskId);
        assertTrue(evidence.passed());
        assertTrue(evidence.citationIds().isEmpty());
        record("no-hit", started, evidence);
    }

    @Test
    void schemaDrift() {
        long started = System.nanoTime();
        TaskProfileSnapshot original = profiles.snapshot("incident-ops");
        List<ToolSnapshot> altered = original.tools().stream()
                .map(tool -> "create_ticket".equals(tool.name())
                        ? new ToolSnapshot(
                                tool.name(),
                                tool.displayName(),
                                tool.description(),
                                tool.parameterSchema(),
                                "0".repeat(64),
                                tool.idempotencyClass(),
                                tool.approvalPolicy(),
                                tool.timeoutMs(),
                                tool.provider())
                        : tool)
                .toList();
        TaskProfileSnapshot drifted = new TaskProfileSnapshot(
                original.profileId(),
                original.profileVersion(),
                original.systemPrompt(),
                original.systemPromptHash(),
                original.knowledgeBaseId(),
                original.knowledgeIndexVersion(),
                original.mcpServerIds(),
                altered);
        TaskEntity task = stateStore.createTask(
                "Schema drift ARGUMENT_SENTINEL", drifted);
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        ToolCall call = new ToolCall(
                "call-create-ticket-schema-drift",
                "create_ticket",
                "{\"title\":\"Drift\",\"severity\":\"critical\","
                        + "\"evidence\":\"FULL_LOG_SENTINEL\"}");
        stateStore.appendAssistant(token, assistantWithCalls(List.of(call)));

        assertThrows(RuntimeException.class, () -> runner.recover(task.getId()));
        AcceptanceEvidence evidence = acceptanceService.evidence(task.getId());
        assertFalse(evidence.passed());
        assertTrue(evidence.ticketId().isEmpty());
        record("schema-drift", started, evidence);
    }

    private static void record(
            String name,
            long started,
            AcceptanceEvidence evidence
    ) {
        AcceptanceReportWriter.record(
                name,
                (System.nanoTime() - started) / 1_000_000,
                evidence);
    }

    private static Map<String, Object> assistantWithCalls(List<ToolCall> calls) {
        List<Map<String, Object>> raw = calls.stream()
                .map(call -> Map.<String, Object>of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of(
                                "name", call.name(),
                                "arguments", call.arguments())))
                .toList();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", raw);
        return message;
    }

    private static final class NoHitLlm implements LlmClient {
        private final AtomicInteger turns = new AtomicInteger();

        @Override
        public Decision chat(
                Context context,
                List<Map<String, Object>> toolSpecs
        ) {
            return next(context);
        }

        @Override
        public Decision chatStream(
                Context context,
                List<Map<String, Object>> toolSpecs,
                Consumer<String> onToken
        ) {
            return next(context);
        }

        private Decision next(Context context) {
            int turn = turns.incrementAndGet();
            if (turn == 1) {
                ToolCall call = new ToolCall(
                        "call-search-no-hit",
                        "search_knowledge",
                        "{\"query\":\"quantum chromodynamics hadron lattice\","
                                + "\"topK\":5}");
                return Decision.tools(
                        assistantWithCalls(List.of(call)),
                        List.of(call));
            }
            if (turn == 2) {
                JsonNode result;
                try {
                    String content = context.messageSnapshot().stream()
                            .filter(message -> "tool".equals(message.get("role")))
                            .map(message -> String.valueOf(message.get("content")))
                            .findFirst()
                            .orElseThrow();
                    result = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(content);
                } catch (Exception failure) {
                    throw new IllegalStateException("No-hit result is invalid", failure);
                }
                if (!result.path("hits").isArray() || !result.path("hits").isEmpty()) {
                    throw new IllegalStateException("No-hit result unexpectedly contains hits");
                }
                String answer = "No matching evidence; no remote action occurred.";
                return Decision.finalAnswer(
                        answer,
                        Map.of("role", "assistant", "content", answer));
            }
            throw new IllegalStateException("Unexpected extra no-hit turn");
        }

        void assertExhausted() {
            if (turns.get() != 2) {
                throw new AssertionError("No-hit script did not consume two turns");
            }
        }
    }
}
