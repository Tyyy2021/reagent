package com.reagent.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.core.Context;
import com.reagent.core.Decision;
import com.reagent.core.FaultPoint;
import com.reagent.core.InjectedWorkerCrashException;
import com.reagent.core.TaskRunToken;
import com.reagent.core.ToolCall;
import com.reagent.llm.LlmClient;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskStatus;
import com.reagent.persist.ToolCallStatus;
import com.reagent.profile.SchemaHasher;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.ToolSchemaDriftException;
import com.reagent.profile.ToolSnapshot;
import com.reagent.testsupport.IncidentScenarioFixture;
import com.reagent.testsupport.LatchingFaultInjector;
import com.reagent.testsupport.PythonCapabilitiesContainer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentWorkflowIT extends IncidentScenarioFixture {

    @Test
    void completesRealRagMcpApprovalAndTicketWorkflow() {
        assertExactIncidentCatalog();
        ScenarioHandle scenario = submitIncident("TASK11-HAPPY-001");

        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        decide(scenario, approval, "APPROVE");
        JsonNode completed = awaitCompleted(scenario);

        PythonCapabilitiesContainer.AcceptanceSnapshot acceptance =
                acceptance(scenario);
        assertEquals(1, acceptance.createTicketAttempts());
        assertEquals(1, acceptance.uniqueTicketCount());
        assertEquals(scenario.script().ticketId(), acceptance.ticketId());
        assertTrue(completed.path("result").asText().contains(
                scenario.script().chunkId()));
        assertTrue(completed.path("result").asText().contains(
                acceptance.ticketId()));
        assertReconstructableLedger(scenario.taskId());
    }

    @Test
    void ragNoHitPersistsAnEmptyObservationAndPerformsNoRemoteWrite() {
        String searchCallId = "call-task11-rag-no-hit";
        ObservationScript script = new ObservationScript(
                searchCallId,
                "search_knowledge",
                "{\"query\":\"quantum chromodynamics hadron lattice gauge theory\","
                        + "\"topK\":5}",
                result -> {
                    JsonNode rag = readJson(result);
                    assertTrue(rag.path("hits").isArray());
                    assertEquals(0, rag.path("hits").size());
                },
                "No matching runbook evidence was found; no external action was taken.",
                false);

        String taskId = submitIncident("TASK11-RAG-NO-HIT-001", script);
        JsonNode completed = awaitTaskStatus(
                taskId, "COMPLETED", SCENARIO_TIMEOUT);

        script.assertExhausted();
        assertEquals(script.finalAnswer(), completed.path("result").asText());
        Context restored = assertReconstructableLedger(taskId);
        assertEquals(
                List.of("system", "user", "assistant", "tool", "assistant"),
                roles(restored));
        assertEquals(
                ToolCallStatus.DONE,
                toolCallRepository.findById(searchCallId).orElseThrow().getStatus());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM approval_request WHERE task_id = ?",
                        Integer.class,
                        taskId));
        assertNoTicket("call-task11-rag-no-hit-unused-ticket");
    }

    @Test
    void pythonUnavailableBecomesADurableObservationWithoutAnUnsafeWrite() {
        String searchCallId = "call-task11-python-unavailable";
        ObservationScript script = new ObservationScript(
                searchCallId,
                "search_knowledge",
                "{\"query\":\"checkout connection pool exhaustion\",\"topK\":3}",
                result -> {
                    assertTrue(result.contains("search_knowledge"));
                    assertTrue(result.contains("RAG service I/O failed after one retry"));
                },
                "The knowledge service was unavailable; investigation stopped without "
                        + "an external action.",
                true);
        String taskId = submitIncident(
                "TASK11-PYTHON-UNAVAILABLE-001", script);
        script.awaitFirstTurn(SCENARIO_TIMEOUT);

        PythonCapabilitiesContainer python = pythonCapabilities();
        boolean paused = false;
        try {
            python.pausePython();
            paused = true;
            script.releaseFirstTurn();
            JsonNode completed = awaitTaskStatus(
                    taskId, "COMPLETED", SCENARIO_TIMEOUT);
            assertEquals(script.finalAnswer(), completed.path("result").asText());
        } finally {
            script.releaseFirstTurn();
            if (paused) {
                python.unpausePython();
            }
        }

        script.assertExhausted();
        Context restored = assertReconstructableLedger(taskId);
        assertEquals(
                List.of("system", "user", "assistant", "tool", "assistant"),
                roles(restored));
        assertEquals(
                ToolCallStatus.DONE,
                toolCallRepository.findById(searchCallId).orElseThrow().getStatus());
        assertNoTicket("call-task11-python-unavailable-unused-ticket");
    }

    @Test
    void schemaDriftFailsClosedBeforeAPersistedTicketCallCanExecute() {
        TaskProfileSnapshot drifted = driftedCreateTicketSnapshot(
                profiles.snapshot("incident-ops"));
        String ticketCallId = "call-task11-schema-drift-ticket";
        TaskEntity task = stateStore.createTask(
                "Task 11 schema drift must fail closed", drifted);
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        stateStore.appendAssistant(
                token,
                assistantWithCalls(List.of(new ToolCall(
                        ticketCallId,
                        "create_ticket",
                        "{\"title\":\"Schema drift probe\","
                                + "\"severity\":\"critical\","
                                + "\"evidence\":\"must never execute\"}"))));

        ToolSchemaDriftException drift = assertThrows(
                ToolSchemaDriftException.class,
                () -> runner.recover(task.getId()));

        assertTrue(drift.getMessage().contains("Tool schema drift for create_ticket"));
        TaskEntity unchanged = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.RUNNING, unchanged.getStatus());
        assertEquals(0, unchanged.getRecoveryCount());
        assertEquals(
                ToolCallStatus.PENDING,
                toolCallRepository.findById(ticketCallId).orElseThrow().getStatus());
        assertEquals(
                0,
                toolCallRepository.findById(ticketCallId).orElseThrow().getAttemptCount());
        Context restored = assertReconstructableLedger(task.getId());
        assertEquals(
                List.of("system", "user", "assistant"),
                roles(restored));
        assertEquals(List.of(ticketCallId), restored.pendingToolCalls().stream()
                .map(ToolCall::id)
                .toList());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM approval_request WHERE task_id = ?",
                        Integer.class,
                        task.getId()));
        assertNoTicket(ticketCallId);
    }

    @Test
    void pythonRestartKeepsTicketAndRagStoresWhileJavaSafelyReplays() {
        PythonCapabilitiesContainer python = pythonCapabilities();
        URI stableEndpoint = python.baseUri();
        assertNotEquals(
                python.backendUri(),
                stableEndpoint,
                "the Java endpoint must not share the restarted backend lifecycle");

        ScenarioHandle scenario = submitIncident("TASK11-PYTHON-RESTART-001");
        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        LatchingFaultInjector.Arm arm = faults.arm(
                FaultPoint.AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT,
                context -> context.toolCallId()
                        .filter(scenario.script().ticketCallId()::equals)
                        .isPresent());

        decide(scenario, approval, "APPROVE");
        arm.awaitReached(SCENARIO_TIMEOUT);
        try {
            PythonCapabilitiesContainer.AcceptanceSnapshot committed =
                    acceptance(scenario);
            assertEquals(1, committed.createTicketAttempts());
            assertEquals(1, committed.uniqueTicketCount());
        } finally {
            arm.releaseCrash();
        }
        awaitDriverStopped(scenario.taskId());

        python.restartPython();
        assertEquals(stableEndpoint, python.baseUri());
        assertNotEquals(python.backendUri(), python.baseUri());
        PythonCapabilitiesContainer.AcceptanceSnapshot afterRestart =
                acceptance(scenario);
        assertEquals(1, afterRestart.createTicketAttempts());
        assertEquals(1, afterRestart.uniqueTicketCount());

        runner.recover(scenario.taskId());
        awaitCompleted(scenario);

        PythonCapabilitiesContainer.AcceptanceSnapshot recovered =
                acceptance(scenario);
        assertEquals(2, recovered.createTicketAttempts());
        assertEquals(1, recovered.uniqueTicketCount());
        assertEquals(afterRestart.ticketId(), recovered.ticketId());
        assertReconstructableLedger(scenario.taskId());
    }

    @Test
    void maximumRecoveryAttemptsStopsBeforeAnotherLlmOrToolCall() {
        RepeatedReadScript script = new RepeatedReadScript();
        installIncidentLlm(script);
        TaskEntity task = stateStore.createTask(
                "Task 11 bounded recovery probe",
                profiles.snapshot("incident-ops"));

        for (int attempt = 1; attempt <= 3; attempt++) {
            String expectedCallId = script.callId(attempt);
            LatchingFaultInjector.Arm arm = faults.arm(
                    FaultPoint.AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER,
                    context -> context.toolCallId()
                            .filter(expectedCallId::equals)
                            .isPresent());
            arm.releaseCrash();

            assertThrows(
                    InjectedWorkerCrashException.class,
                    () -> runner.recover(task.getId()));

            TaskEntity running = taskRepository.findById(task.getId()).orElseThrow();
            assertEquals(TaskStatus.RUNNING, running.getStatus());
            assertEquals(attempt, running.getRecoveryCount());
            assertEquals(
                    ToolCallStatus.DONE,
                    toolCallRepository.findById(expectedCallId)
                            .orElseThrow()
                            .getStatus());
            assertReconstructableLedger(task.getId());
        }

        String stopped = runner.recover(task.getId());

        TaskEntity failed = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals("超过最大自动恢复次数,已止损标记 FAILED。", stopped);
        assertEquals(TaskStatus.FAILED, failed.getStatus());
        assertEquals(3, failed.getRecoveryCount());
        assertTrue(failed.getResult().contains("最大自动恢复次数(3)"));
        script.assertTurns(3);
        Context restored = assertReconstructableLedger(task.getId());
        assertEquals(8, restored.size());
        assertTrue(restored.pendingToolCalls().isEmpty());
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM approval_request WHERE task_id = ?",
                        Integer.class,
                        task.getId()));
        assertNoTicket("call-task11-max-unused-ticket");
    }

    private void assertNoTicket(String idempotencyKey) {
        PythonCapabilitiesContainer.AcceptanceSnapshot acceptance =
                acceptance(idempotencyKey);
        assertEquals(0, acceptance.createTicketAttempts());
        assertEquals(0, acceptance.uniqueTicketCount());
    }

    private TaskProfileSnapshot driftedCreateTicketSnapshot(
            TaskProfileSnapshot current
    ) {
        SchemaHasher hasher = new SchemaHasher(mapper);
        List<ToolSnapshot> tools = current.tools().stream()
                .map(tool -> {
                    if (!"create_ticket".equals(tool.name())) {
                        return tool;
                    }
                    Map<String, Object> schema =
                            new LinkedHashMap<>(tool.parameterSchema());
                    schema.put("task11_schema_drift", true);
                    return new ToolSnapshot(
                            tool.name(),
                            tool.displayName(),
                            tool.description(),
                            schema,
                            hasher.hash(schema),
                            tool.idempotencyClass(),
                            tool.approvalPolicy(),
                            tool.timeoutMs(),
                            tool.provider());
                })
                .toList();
        return new TaskProfileSnapshot(
                current.profileId(),
                current.profileVersion(),
                current.systemPrompt(),
                current.systemPromptHash(),
                current.knowledgeBaseId(),
                current.knowledgeIndexVersion(),
                current.mcpServerIds(),
                tools);
    }

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (IOException exception) {
            throw new AssertionError("Expected a JSON tool observation", exception);
        }
    }

    private static List<String> roles(Context context) {
        return context.messageSnapshot().stream()
                .map(message -> String.valueOf(message.get("role")))
                .toList();
    }

    private static Map<String, Object> assistantWithCalls(
            List<ToolCall> calls
    ) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put(
                "tool_calls",
                calls.stream()
                        .map(call -> Map.<String, Object>of(
                                "id", call.id(),
                                "type", "function",
                                "function", Map.of(
                                        "name", call.name(),
                                        "arguments", call.arguments())))
                        .toList());
        return assistant;
    }

    private static void assertExactCatalog(
            List<Map<String, Object>> toolSpecs
    ) {
        Set<String> names = toolSpecs.stream()
                .map(spec -> {
                    Object function = spec.get("function");
                    if (!(function instanceof Map<?, ?> details)) {
                        throw new AssertionError("Missing tool function spec");
                    }
                    return String.valueOf(details.get("name"));
                })
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertEquals(Set.copyOf(INCIDENT_TOOLS), names);
    }

    private static void assertRoles(
            List<Map<String, Object>> messages,
            String... expected
    ) {
        assertEquals(
                List.of(expected),
                messages.stream()
                        .map(message -> String.valueOf(message.get("role")))
                        .toList());
    }

    private static final class ObservationScript implements LlmClient {
        private final String callId;
        private final String toolName;
        private final String arguments;
        private final Consumer<String> observation;
        private final String finalAnswer;
        private final CountDownLatch firstTurnEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstTurn;
        private final AtomicInteger turns = new AtomicInteger();

        private ObservationScript(
                String callId,
                String toolName,
                String arguments,
                Consumer<String> observation,
                String finalAnswer,
                boolean blockFirstTurn
        ) {
            this.callId = callId;
            this.toolName = toolName;
            this.arguments = arguments;
            this.observation = observation;
            this.finalAnswer = finalAnswer;
            this.releaseFirstTurn = new CountDownLatch(blockFirstTurn ? 1 : 0);
        }

        @Override
        public Decision chat(
                Context context,
                List<Map<String, Object>> toolSpecs
        ) {
            return next(context, toolSpecs);
        }

        @Override
        public Decision chatStream(
                Context context,
                List<Map<String, Object>> toolSpecs,
                Consumer<String> onToken
        ) {
            return next(context, toolSpecs);
        }

        private synchronized Decision next(
                Context context,
                List<Map<String, Object>> toolSpecs
        ) {
            assertExactCatalog(toolSpecs);
            List<Map<String, Object>> messages = context.messageSnapshot();
            int turn = turns.incrementAndGet();
            if (turn == 1) {
                assertRoles(messages, "system", "user");
                firstTurnEntered.countDown();
                await(releaseFirstTurn, "first incident observation turn");
                ToolCall call = new ToolCall(callId, toolName, arguments);
                return Decision.tools(
                        assistantWithCalls(List.of(call)),
                        List.of(call));
            }
            if (turn == 2) {
                assertRoles(messages, "system", "user", "assistant", "tool");
                String result = messages.stream()
                        .filter(message -> "tool".equals(message.get("role")))
                        .filter(message -> callId.equals(
                                message.get("tool_call_id")))
                        .map(message -> String.valueOf(message.get("content")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "Missing durable observation for " + callId));
                observation.accept(result);
                return Decision.finalAnswer(
                        finalAnswer,
                        Map.of("role", "assistant", "content", finalAnswer));
            }
            throw new AssertionError("Unexpected observation-script turn " + turn);
        }

        private void awaitFirstTurn(Duration timeout) {
            await(firstTurnEntered, timeout, "first LLM turn");
        }

        private void releaseFirstTurn() {
            releaseFirstTurn.countDown();
        }

        private String finalAnswer() {
            return finalAnswer;
        }

        private void assertExhausted() {
            assertEquals(2, turns.get());
        }
    }

    private static final class RepeatedReadScript implements LlmClient {
        private final AtomicInteger turns = new AtomicInteger();

        @Override
        public Decision chat(
                Context context,
                List<Map<String, Object>> toolSpecs
        ) {
            return next(context, toolSpecs);
        }

        @Override
        public Decision chatStream(
                Context context,
                List<Map<String, Object>> toolSpecs,
                Consumer<String> onToken
        ) {
            return next(context, toolSpecs);
        }

        private synchronized Decision next(
                Context context,
                List<Map<String, Object>> toolSpecs
        ) {
            assertExactCatalog(toolSpecs);
            int turn = turns.incrementAndGet();
            if (turn > 3) {
                throw new AssertionError(
                        "Recovery cap made an extra LLM call");
            }
            assertEquals(
                    2 + ((turn - 1) * 2),
                    context.messageSnapshot().size());
            ToolCall call = new ToolCall(
                    callId(turn),
                    "query_metrics",
                    "{\"service\":\"checkout\","
                            + "\"start\":\"2026-07-19T10:00:00Z\","
                            + "\"end\":\"2026-07-19T10:15:00Z\"}");
            return Decision.tools(
                    assistantWithCalls(List.of(call)),
                    List.of(call));
        }

        private String callId(int turn) {
            return "call-task11-max-recovery-" + turn;
        }

        private void assertTurns(int expected) {
            assertEquals(expected, turns.get());
        }
    }

    private static void await(
            CountDownLatch latch,
            String label
    ) {
        await(latch, SCENARIO_TIMEOUT, label);
    }

    private static void await(
            CountDownLatch latch,
            Duration timeout,
            String label
    ) {
        try {
            assertTrue(
                    latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS),
                    label + " did not occur within " + timeout);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(
                    "Interrupted while awaiting " + label,
                    exception);
        }
    }
}
