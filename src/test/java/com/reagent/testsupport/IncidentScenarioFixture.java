package com.reagent.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.approval.ApprovalDecisionTransaction;
import com.reagent.approval.ApprovalRequestRepository;
import com.reagent.core.AgentRunner;
import com.reagent.core.Context;
import com.reagent.core.Decision;
import com.reagent.core.DefaultToolBatchCoordinator;
import com.reagent.core.FaultInjector;
import com.reagent.core.InFlightTasks;
import com.reagent.core.ShutdownState;
import com.reagent.core.TaskControl;
import com.reagent.core.ToolCall;
import com.reagent.core.WorkerIdentity;
import com.reagent.llm.LlmClient;
import com.reagent.persist.MessageRepository;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskLeaseGuard;
import com.reagent.persist.TaskRepository;
import com.reagent.persist.ToolCallRepository;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.ToolCatalogResolver;
import com.reagent.sandbox.WorkspaceStore;
import com.reagent.stream.StreamTransport;
import com.reagent.tool.ToolExecutor;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(IncidentScenarioFixture.TestBeans.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IncidentScenarioFixture extends InfrastructureIT {

    protected static final List<String> INCIDENT_TOOLS = List.of(
            "search_knowledge", "query_metrics", "search_logs", "create_ticket");
    protected static final Duration SCENARIO_TIMEOUT = Duration.ofSeconds(45);

    private static final PythonCapabilitiesContainer PYTHON =
            PythonCapabilitiesContainer.shared();
    private static final AtomicReference<URI> CAPABILITY_BASE_URI_OVERRIDE =
            new AtomicReference<>();

    @DynamicPropertySource
    static void incidentProperties(DynamicPropertyRegistry registry) {
        registry.add("reagent.worker.id", () -> "incident-worker-a");
        registry.add(
                "reagent.rag.base-url",
                () -> capabilityBaseUri().toString());
        registry.add(
                "reagent.mcp.servers.fake-ops.base-url",
                () -> capabilityBaseUri().toString());
        registry.add(
                "reagent.mcp.servers.fake-ops.request-timeout",
                () -> "30s");
    }

    @LocalServerPort
    private int port;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected AgentProfileRegistry profiles;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected SwitchableIncidentLlm llm;

    @Autowired
    protected LatchingFaultInjector faults;

    @Autowired
    protected AgentRunner runner;

    @Autowired
    protected InFlightTasks inFlight;

    @Autowired
    protected StateStore stateStore;

    @Autowired
    protected TaskControl taskControl;

    @Autowired
    protected TaskRepository taskRepository;

    @Autowired
    protected MessageRepository messageRepository;

    @Autowired
    protected ToolCallRepository toolCallRepository;

    @Autowired
    protected ApprovalRequestRepository approvalRequestRepository;

    @Autowired
    protected ToolCatalogResolver catalogResolver;

    @Autowired
    protected ToolExecutor toolExecutor;

    @Autowired
    protected WorkspaceStore workspaceStore;

    @Autowired
    protected StreamTransport streamTransport;

    @Autowired
    protected ApprovalDecisionTransaction approvalDecisionTransaction;

    @Autowired
    protected Tracer tracer;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @BeforeEach
    void prepareIncidentRuntime() {
        clearIncidentRuntime();
    }

    @AfterEach
    void clearIncidentRuntimeAfterTest() {
        clearIncidentRuntime();
    }

    private void clearIncidentRuntime() {
        faults.clear();
        llm.clear();
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM approval_request");
        jdbc.update("DELETE FROM incident_intake");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM task");
    }

    protected void assertExactIncidentCatalog() {
        TaskProfileSnapshot snapshot = profiles.snapshot("incident-ops");
        assertEquals(
                INCIDENT_TOOLS,
                snapshot.tools().stream().map(tool -> tool.name()).toList());
        assertEquals(List.of("fake-ops"), snapshot.mcpServerIds());
    }

    protected ScenarioHandle submitIncident(String externalAlertId) {
        StrictIncidentScript script = new StrictIncidentScript(mapper, externalAlertId);
        return new ScenarioHandle(submitIncident(externalAlertId, script), script);
    }

    protected String submitIncident(
            String externalAlertId,
            LlmClient incidentLlm
    ) {
        installIncidentLlm(incidentLlm);
        JsonNode response = post(
                "/api/incidents",
                Map.of(
                        "source", "fake-alertmanager",
                        "externalAlertId", externalAlertId,
                        "service", "checkout",
                        "severity", "critical",
                        "title", "Checkout connection pool exhaustion",
                        "summary",
                        "Checkout error rate and latency increased while the pool was saturated.",
                        "startedAt", "2026-07-19T10:00:00Z",
                        "labels", Map.of("environment", "demo", "region", "local")),
                202);
        String taskId = response.path("taskId").asText();
        assertFalse(taskId.isBlank());
        return taskId;
    }

    protected void installIncidentLlm(LlmClient incidentLlm) {
        llm.use(incidentLlm);
    }

    protected ApprovalSnapshot awaitPendingApproval(ScenarioHandle scenario) {
        awaitTaskStatus(scenario.taskId(), "WAITING_APPROVAL", SCENARIO_TIMEOUT);
        Instant deadline = Instant.now().plus(SCENARIO_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            JsonNode approvals = get(
                    "/api/tasks/" + scenario.taskId() + "/approvals",
                    200);
            if (approvals.isArray() && approvals.size() == 1) {
                JsonNode approval = approvals.get(0);
                if ("PENDING".equals(approval.path("status").asText())) {
                    ApprovalSnapshot snapshot = new ApprovalSnapshot(
                            approval.path("toolCallId").asText(),
                            approval.path("toolName").asText(),
                            approval.path("status").asText());
                    assertEquals(scenario.script().ticketCallId(), snapshot.toolCallId());
                    assertEquals("create_ticket", snapshot.toolName());
                    return snapshot;
                }
            }
            pausePolling();
        }
        throw new AssertionError("Pending approval did not appear for " + scenario.taskId());
    }

    protected void decide(
            ScenarioHandle scenario,
            ApprovalSnapshot approval,
            String decision
    ) {
        JsonNode response = post(
                "/api/tasks/" + scenario.taskId()
                        + "/approvals/" + approval.toolCallId() + "/decision",
                Map.of("decision", decision, "reason", "Task 11 deterministic scenario"),
                200);
        assertEquals(
                "APPROVE".equals(decision) ? "APPROVED" : "REJECTED",
                response.path("status").asText());
    }

    protected JsonNode awaitCompleted(ScenarioHandle scenario) {
        JsonNode task = awaitTaskStatus(
                scenario.taskId(), "COMPLETED", SCENARIO_TIMEOUT);
        scenario.script().assertExhausted();
        assertEquals(scenario.script().finalAnswer(), task.path("result").asText());
        return task;
    }

    protected PythonCapabilitiesContainer.AcceptanceSnapshot acceptance(
            ScenarioHandle scenario
    ) {
        return acceptance(scenario.script().ticketCallId());
    }

    protected PythonCapabilitiesContainer.AcceptanceSnapshot acceptance(
            String idempotencyKey
    ) {
        return PYTHON.acceptance(idempotencyKey);
    }

    protected PythonCapabilitiesContainer pythonCapabilities() {
        return PYTHON;
    }

    protected static void useCapabilityBaseUri(URI uri) {
        CAPABILITY_BASE_URI_OVERRIDE.set(Objects.requireNonNull(uri, "uri"));
    }

    protected static void resetCapabilityBaseUri() {
        CAPABILITY_BASE_URI_OVERRIDE.set(null);
    }

    private static URI capabilityBaseUri() {
        URI override = CAPABILITY_BASE_URI_OVERRIDE.get();
        return override == null ? PYTHON.baseUri() : override;
    }

    protected void awaitDriverStopped(String taskId) {
        Instant deadline = Instant.now().plus(SCENARIO_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!inFlight.isRunning(taskId)) {
                return;
            }
            pausePolling();
        }
        throw new AssertionError(
                "Task driver did not stop within " + SCENARIO_TIMEOUT + ": " + taskId);
    }

    protected IncidentWorkerRuntime worker(
            String workerId,
            Clock clock,
            int maxRecoveryAttempts
    ) {
        WorkerIdentity identity = new WorkerIdentity(workerId, "0");
        StateStore target = new StateStore(
                taskRepository,
                messageRepository,
                toolCallRepository,
                mapper,
                profiles,
                identity,
                30_000,
                clock,
                new TaskLeaseGuard(taskRepository),
                approvalRequestRepository);
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager,
                new AnnotationTransactionAttributeSource()));
        StateStore workerStore = (StateStore) proxyFactory.getProxy();
        TaskControl workerControl = new TaskControl();
        DefaultToolBatchCoordinator coordinator =
                new DefaultToolBatchCoordinator(
                        workerStore,
                        toolExecutor,
                        streamTransport,
                        workerControl,
                        FaultInjector.none(),
                        approvalDecisionTransaction);
        AgentRunner workerRunner = new AgentRunner(
                llm,
                profiles,
                catalogResolver,
                coordinator,
                FaultInjector.none(),
                workerStore,
                new ShutdownState(),
                workspaceStore,
                new InFlightTasks(),
                streamTransport,
                workerControl,
                tracer,
                identity,
                maxRecoveryAttempts);
        return new IncidentWorkerRuntime(workerStore, workerRunner, workerControl);
    }

    protected Context assertReconstructableLedger(String taskId) {
        Context restored = stateStore.loadContext(taskId);
        List<Map<String, Object>> messages = restored.messageSnapshot();
        assertEquals(messageRepository.countByTaskId(taskId), messages.size());

        Set<String> declaredCallIds = new LinkedHashSet<>();
        Set<String> resultCallIds = new LinkedHashSet<>();
        int toolMessageCount = 0;
        for (Map<String, Object> message : messages) {
            if ("assistant".equals(message.get("role"))
                    && message.get("tool_calls") != null) {
                for (ToolCall call : ToolCall.parseAssistantToolCalls(
                        message.get("tool_calls"))) {
                    assertTrue(
                            declaredCallIds.add(call.id()),
                            "tool call ID must be unique in reconstructed context");
                }
            } else if ("tool".equals(message.get("role"))) {
                toolMessageCount++;
                assertTrue(
                        resultCallIds.add(String.valueOf(message.get("tool_call_id"))),
                        "tool result must be materialized at most once");
            }
        }

        Set<String> ledgerCallIds = toolCallRepository.findAll().stream()
                .filter(call -> taskId.equals(call.getTaskId()))
                .map(call -> call.getId())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        assertEquals(declaredCallIds, ledgerCallIds);
        assertTrue(declaredCallIds.containsAll(resultCallIds));
        assertEquals(resultCallIds.size(), toolMessageCount);
        restored.pendingToolCalls();
        return restored;
    }

    protected JsonNode awaitTaskStatus(
            String taskId,
            String expectedStatus,
            Duration timeout
    ) {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = get("/api/tasks/" + taskId, 200);
            if (expectedStatus.equals(last.path("status").asText())) {
                return last;
            }
            pausePolling();
        }
        JsonNode observed = last;
        throw new AssertionError(
                "Task " + taskId + " did not reach " + expectedStatus
                        + " within " + timeout + "; last="
                        + (observed == null ? "none" : observed));
    }

    protected JsonNode get(String path, int expectedStatus) {
        return exchange(
                HttpRequest.newBuilder(applicationUri(path))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build(),
                expectedStatus);
    }

    protected JsonNode post(String path, Object body, int expectedStatus) {
        try {
            return exchange(
                    HttpRequest.newBuilder(applicationUri(path))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body)))
                            .build(),
                    expectedStatus);
        } catch (IOException exception) {
            throw new AssertionError("Cannot serialize Task 11 HTTP request", exception);
        }
    }

    private JsonNode exchange(HttpRequest request, int expectedStatus) {
        try {
            HttpResponse<String> response = http.send(
                    request, HttpResponse.BodyHandlers.ofString());
            assertEquals(
                    expectedStatus,
                    response.statusCode(),
                    () -> request.uri() + " returned " + response.statusCode()
                            + " body=" + response.body());
            return mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new AssertionError("Task 11 HTTP request failed: " + request.uri(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(
                    "Task 11 HTTP request was interrupted: " + request.uri(),
                    exception);
        }
    }

    private URI applicationUri(String path) {
        return URI.create("http://127.0.0.1:" + port).resolve(path);
    }

    private static void pausePolling() {
        try {
            Thread.sleep(Duration.ofMillis(50));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while polling Task 11 state", exception);
        }
    }

    public record ScenarioHandle(String taskId, StrictIncidentScript script) {
    }

    public record ApprovalSnapshot(
            String toolCallId,
            String toolName,
            String status
    ) {
    }

    public record IncidentWorkerRuntime(
            StateStore stateStore,
            AgentRunner runner,
            TaskControl taskControl
    ) {
    }

    public static final class StrictIncidentScript implements LlmClient {

        private final ObjectMapper mapper;
        private final String externalAlertId;
        private final String knowledgeCallId;
        private final String metricsCallId;
        private final String logsCallId;
        private final String ticketCallId;
        private final AtomicInteger turn = new AtomicInteger();

        private volatile String chunkId;
        private volatile String ticketId;
        private volatile String finalAnswer;

        StrictIncidentScript(ObjectMapper mapper, String externalAlertId) {
            this.mapper = Objects.requireNonNull(mapper, "mapper");
            this.externalAlertId = Objects.requireNonNull(
                    externalAlertId, "externalAlertId");
            String prefix = "call-" + digest(externalAlertId).substring(0, 24);
            this.knowledgeCallId = prefix + "-knowledge";
            this.metricsCallId = prefix + "-metrics";
            this.logsCallId = prefix + "-logs";
            this.ticketCallId = prefix + "-ticket";
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
            Objects.requireNonNull(onToken, "onToken");
            return next(context, toolSpecs);
        }

        public String ticketCallId() {
            return ticketCallId;
        }

        public String ticketId() {
            return ticketId;
        }

        public String chunkId() {
            return chunkId;
        }

        public String finalAnswer() {
            return finalAnswer;
        }

        public void assertExhausted() {
            assertEquals(4, turn.get(), "strict incident script must consume four turns");
            assertTrue(chunkId != null && !chunkId.isBlank());
            assertTrue(finalAnswer != null && !finalAnswer.isBlank());
        }

        private synchronized Decision next(
                Context context,
                List<Map<String, Object>> toolSpecs
        ) {
            assertEquals(Set.copyOf(INCIDENT_TOOLS), exactToolNames(toolSpecs));
            List<Map<String, Object>> messages = context.messageSnapshot();
            int current = turn.incrementAndGet();
            return switch (current) {
                case 1 -> knowledgeDecision(messages);
                case 2 -> observationsDecision(messages);
                case 3 -> ticketDecision(messages);
                case 4 -> finalDecision(messages);
                default -> throw new AssertionError(
                        "Unexpected extra incident LLM turn " + current);
            };
        }

        private Decision knowledgeDecision(List<Map<String, Object>> messages) {
            assertRoles(messages, "system", "user");
            assertTrue(
                    String.valueOf(messages.getLast().get("content"))
                            .contains(externalAlertId),
                    "incident goal must contain the exact external alert ID");
            ToolCall call = new ToolCall(
                    knowledgeCallId,
                    "search_knowledge",
                    "{\"query\":\"checkout connection pool exhaustion\",\"topK\":3}");
            return Decision.tools(assistantWithCalls(List.of(call)), List.of(call));
        }

        private Decision observationsDecision(List<Map<String, Object>> messages) {
            assertRoles(messages, "system", "user", "assistant", "tool");
            JsonNode rag = parseToolResult(messages, knowledgeCallId);
            assertTrue(rag.path("hits").isArray() && !rag.path("hits").isEmpty());
            chunkId = rag.path("hits").get(0).path("chunkId").asText();
            assertFalse(chunkId.isBlank());

            ToolCall metrics = new ToolCall(
                    metricsCallId,
                    "query_metrics",
                    "{\"service\":\"checkout\","
                            + "\"start\":\"2026-07-19T10:00:00Z\","
                            + "\"end\":\"2026-07-19T10:15:00Z\"}");
            ToolCall logs = new ToolCall(
                    logsCallId,
                    "search_logs",
                    "{\"service\":\"checkout\","
                            + "\"start\":\"2026-07-19T10:00:00Z\","
                            + "\"end\":\"2026-07-19T10:15:00Z\","
                            + "\"query\":\"SQLTransientConnectionException\","
                            + "\"limit\":2}");
            return Decision.tools(
                    assistantWithCalls(List.of(metrics, logs)),
                    List.of(metrics, logs));
        }

        private Decision ticketDecision(List<Map<String, Object>> messages) {
            assertRoles(
                    messages,
                    "system", "user", "assistant", "tool",
                    "assistant", "tool", "tool");
            JsonNode metrics = parseToolResult(messages, metricsCallId);
            JsonNode logs = parseToolResult(messages, logsCallId);
            assertEquals(14.2, metrics.path("errorRatePercent").asDouble());
            assertTrue(logs.path("entries").isArray());
            assertTrue(logs.path("entries").size() >= 1);
            assertTrue(
                    logs.path("entries").get(0).path("line").asText()
                            .contains("Connection is not available"));

            ToolCall ticket = new ToolCall(
                    ticketCallId,
                    "create_ticket",
                    "{\"title\":\"Checkout connection pool exhausted\","
                            + "\"severity\":\"critical\","
                            + "\"evidence\":\"Citation " + chunkId
                            + "; errorRatePercent=14.2; "
                            + "SQLTransientConnectionException: Connection is not available\"}");
            assertFalse(ticket.arguments().contains("idempotency_key"));
            return Decision.tools(
                    assistantWithCalls(List.of(ticket)),
                    List.of(ticket));
        }

        private Decision finalDecision(List<Map<String, Object>> messages) {
            assertRoles(
                    messages,
                    "system", "user", "assistant", "tool",
                    "assistant", "tool", "tool", "assistant", "tool");
            String result = toolResult(messages, ticketCallId);
            if (result.startsWith("{")) {
                JsonNode ticket = parse(result);
                ticketId = ticket.path("ticketId").asText();
                assertFalse(ticketId.isBlank());
                finalAnswer = "Checkout connection pool exhaustion confirmed; citation "
                        + chunkId
                        + "; error rate 14.2%; log: Connection is not available; "
                        + "approval APPROVED; ticket " + ticketId + ".";
            } else {
                assertTrue(result.contains("Approval rejected"));
                assertTrue(result.contains("no remote action occurred"));
                ticketId = null;
                finalAnswer = "Checkout connection pool exhaustion confirmed; citation "
                        + chunkId
                        + "; error rate 14.2%; log: Connection is not available; "
                        + "approval REJECTED; no ticket was created.";
            }
            return Decision.finalAnswer(
                    finalAnswer,
                    Map.of("role", "assistant", "content", finalAnswer));
        }

        private JsonNode parseToolResult(
                List<Map<String, Object>> messages,
                String callId
        ) {
            return parse(toolResult(messages, callId));
        }

        private JsonNode parse(String json) {
            try {
                return mapper.readTree(json);
            } catch (IOException exception) {
                throw new AssertionError("Tool result is not valid JSON", exception);
            }
        }

        private static String toolResult(
                List<Map<String, Object>> messages,
                String callId
        ) {
            return messages.stream()
                    .filter(message -> "tool".equals(message.get("role")))
                    .filter(message -> callId.equals(message.get("tool_call_id")))
                    .map(message -> String.valueOf(message.get("content")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Missing persisted tool result for " + callId));
        }

        private static Set<String> exactToolNames(
                List<Map<String, Object>> specs
        ) {
            List<String> names = new ArrayList<>();
            for (Map<String, Object> spec : specs) {
                if (!"function".equals(spec.get("type"))) {
                    throw new AssertionError("Tool spec type must be function");
                }
                Object functionValue = spec.get("function");
                if (!(functionValue instanceof Map<?, ?> function)) {
                    throw new AssertionError("Tool spec function is required");
                }
                Object nameValue = function.get("name");
                if (!(nameValue instanceof String name) || name.isBlank()) {
                    throw new AssertionError("Tool spec name is required");
                }
                if (!(function.get("parameters") instanceof Map<?, ?>)) {
                    throw new AssertionError("Tool spec parameters are required for " + name);
                }
                names.add(name);
            }
            if (Set.copyOf(names).size() != names.size()) {
                throw new AssertionError("Tool specs contain duplicate names");
            }
            return Set.copyOf(names);
        }

        private static void assertRoles(
                List<Map<String, Object>> messages,
                String... expected
        ) {
            assertEquals(
                    List.of(expected),
                    messages.stream().map(message -> message.get("role")).toList());
        }

        private static Map<String, Object> assistantWithCalls(
                List<ToolCall> calls
        ) {
            List<Map<String, Object>> rawCalls = calls.stream()
                    .map(call -> Map.<String, Object>of(
                            "id", call.id(),
                            "type", "function",
                            "function", Map.of(
                                    "name", call.name(),
                                    "arguments", call.arguments())))
                    .toList();
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", null);
            assistant.put("tool_calls", rawCalls);
            return assistant;
        }

        private static String digest(String value) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }

    public static final class SwitchableIncidentLlm implements LlmClient {
        private final AtomicReference<LlmClient> delegate = new AtomicReference<>();

        void use(LlmClient client) {
            if (!delegate.compareAndSet(null, Objects.requireNonNull(client, "client"))) {
                throw new IllegalStateException("An incident LLM script is already installed");
            }
        }

        void clear() {
            delegate.set(null);
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
            LlmClient current = delegate.get();
            if (current == null) {
                throw new AssertionError("No Task 11 incident LLM script is installed");
            }
            return current;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        SwitchableIncidentLlm incidentLlm() {
            return new SwitchableIncidentLlm();
        }

        @Bean
        LatchingFaultInjector latchingFaultInjector() {
            return new LatchingFaultInjector();
        }
    }
}
