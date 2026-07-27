package com.reagent.obs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.acceptance.AcceptanceEvidence;
import com.reagent.acceptance.AcceptanceService;
import com.reagent.core.FaultPoint;
import com.reagent.testsupport.IncidentScenarioFixture;
import com.reagent.testsupport.LatchingFaultInjector;
import com.reagent.testsupport.PythonCapabilitiesContainer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossServiceTraceIT extends IncidentScenarioFixture {

    private static final String SECRET = "SECRET_SENTINEL";
    private static final String ARGUMENT = "ARGUMENT_SENTINEL";
    private static final String FULL_LOG = "FULL_LOG_SENTINEL";
    private static final String EXTERNAL_ALERT_ID =
            "TASK12-TRACE-" + SECRET + "-" + ARGUMENT + "-" + FULL_LOG;

    private static final PythonCapabilitiesContainer SHARED_CAPABILITIES =
            PythonCapabilitiesContainer.shared();
    private static final FailureSafeTraceInfrastructure<
            GenericContainer<?>, PythonRuntime> TRACE_INFRASTRUCTURE =
            startTraceInfrastructure();
    private static final GenericContainer<?> JAEGER =
            TRACE_INFRASTRUCTURE.jaeger();
    private static final PythonRuntime PYTHON =
            TRACE_INFRASTRUCTURE.python();

    @Autowired
    private AcceptanceService acceptanceService;

    @Autowired
    private OpenTelemetry openTelemetry;

    @DynamicPropertySource
    static void traceProperties(DynamicPropertyRegistry registry) {
        registry.add("reagent.tracing.enabled", () -> "true");
        registry.add("reagent.tracing.otlp-endpoint", CrossServiceTraceIT::otlpEndpoint);
        registry.add("reagent.tracing.service-name", () -> "reagent");
    }

    @Test
    void dangerousWorkerFailoverExportsOneRuntimeTraceAcrossJavaAndPython()
            throws Exception {
        ScenarioHandle scenario = submitIncident(EXTERNAL_ALERT_ID);
        assertEquals(
                INCIDENT_TOOLS,
                stateStore.loadProfile(scenario.taskId()).tools().stream()
                        .map(tool -> tool.name())
                        .toList());
        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        LatchingFaultInjector.Arm crash = faults.arm(
                FaultPoint.AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT,
                context -> "incident-worker-a".equals(context.workerId())
                        && context.toolCallId()
                        .filter(scenario.script().ticketCallId()::equals)
                        .isPresent());

        decide(scenario, approval, "APPROVE");
        crash.awaitReached(SCENARIO_TIMEOUT);
        crash.releaseCrash();
        awaitDriverStopped(scenario.taskId());

        IncidentWorkerRuntime workerB = worker(
                "incident-worker-b",
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(2)),
                3);
        String recovered = workerB.runner().recover(scenario.taskId());
        awaitCompleted(scenario);

        AcceptanceEvidence evidence = acceptanceService.evidence(scenario.taskId());
        assertTrue(evidence.passed());
        assertTrue(evidence.createTicketAttempts() >= 2);
        assertEquals(1, evidence.uniqueTicketCount());
        assertEquals(scenario.script().finalAnswer(), recovered);

        forceJavaFlush();
        JsonNode trace = awaitRuntimeTrace(
                URI.create(jaegerQueryBase() + "/api/traces/"
                        + Trace.traceIdFrom(scenario.taskId())),
                Duration.ofSeconds(45));
        assertRuntimeTrace(trace);
    }

    @AfterAll
    void stopTraceInfrastructure() {
        try {
            TRACE_INFRASTRUCTURE.close();
        } finally {
            resetCapabilityBaseUri();
        }
    }

    private void forceJavaFlush() {
        assertTrue(
                openTelemetry instanceof OpenTelemetrySdk,
                "trace test must use the real Java SDK");
        OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetry;
        assertTrue(sdk.getSdkTracerProvider().forceFlush()
                .join(10, TimeUnit.SECONDS)
                .isSuccess());
    }

    private static void assertRuntimeTrace(JsonNode response) throws Exception {
        JsonNode data = response.path("data").get(0);
        Set<String> services = new HashSet<>();
        data.path("processes").forEach(process ->
                services.add(process.path("serviceName").asText()));

        Set<String> spanNames = new HashSet<>();
        Set<Long> epochs = new HashSet<>();
        Set<String> workers = new HashSet<>();
        Map<String, Set<String>> mcpToolsBySpan = new HashMap<>();
        data.path("spans").forEach(span -> {
            String spanName = span.path("operationName").asText();
            spanNames.add(spanName);
            span.path("tags").forEach(tag -> {
                String key = tag.path("key").asText();
                if ("reagent.worker.epoch".equals(key)) {
                    epochs.add(tag.path("value").asLong());
                } else if ("reagent.worker.id".equals(key)) {
                    workers.add(tag.path("value").asText());
                } else if ("gen_ai.tool.name".equals(key)
                        || "mcp.tool".equals(key)) {
                    mcpToolsBySpan.computeIfAbsent(
                                    spanName, ignored -> new HashSet<>())
                            .add(tag.path("value").asText());
                }
            });
        });

        assertEquals(Set.of("reagent", "agent-capabilities"), services);
        assertTrue(workers.containsAll(Set.of(
                "incident-worker-a", "incident-worker-b")));
        assertTrue(epochs.size() >= 2);
        Set<String> requiredSpans = Set.of(
                "agent.task",
                "agent.step",
                "agent.recovery",
                "approval.wait",
                "approval.decision",
                "rag.http",
                "mcp.initialize",
                "mcp.list_tools",
                "mcp.call_tool",
                "rag.search",
                "mcp.query_metrics",
                "mcp.search_logs",
                "mcp.create_ticket",
                "ticket.insert_or_read");
        Set<String> missingSpans = new HashSet<>(requiredSpans);
        missingSpans.removeAll(spanNames);
        assertTrue(
                missingSpans.isEmpty(),
                () -> "missing runtime spans=" + missingSpans
                        + "; observed=" + spanNames
                        + "; other trace ids="
                        + traceIdsByOperation(missingSpans));
        assertTrue(mcpToolsBySpan.getOrDefault(
                        "mcp.call_tool", Set.of())
                .containsAll(Set.of(
                        "query_metrics", "search_logs", "create_ticket")));
        assertTrue(mcpToolsBySpan.getOrDefault(
                        "mcp.create_ticket", Set.of())
                .contains("create_ticket"));

        String exported = response.toString();
        assertTrue(exported.contains(Trace.RECOVERY_ATTEMPT));
        assertFalse(exported.contains(SECRET));
        assertFalse(exported.contains(ARGUMENT));
        assertFalse(exported.contains(FULL_LOG));
    }

    private static Map<String, Set<String>> traceIdsByOperation(
            Set<String> operations
    ) {
        Map<String, Set<String>> traceIds = new HashMap<>();
        if (operations.isEmpty()) {
            return traceIds;
        }
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    jaegerQueryBase()
                                            + "/api/traces?service=reagent&limit=100"))
                            .timeout(Duration.ofSeconds(5))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Map.of("query-status-" + response.statusCode(), Set.of());
            }
            JsonNode body = new ObjectMapper().readTree(response.body());
            body.path("data").forEach(trace -> {
                String traceId = trace.path("traceID").asText();
                trace.path("spans").forEach(span -> {
                    String operation = span.path("operationName").asText();
                    if (operations.contains(operation)) {
                        traceIds.computeIfAbsent(
                                        operation, ignored -> new HashSet<>())
                                .add(traceId);
                    }
                });
            });
            return traceIds;
        } catch (Exception failure) {
            return Map.of(
                    "query-failure",
                    Set.of(failure.getClass().getSimpleName()));
        }
    }

    private static JsonNode awaitRuntimeTrace(URI uri, Duration timeout)
            throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                last = mapper.readTree(response.body());
                if (hasRuntimeCore(last)) {
                    return last;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError(
                "Jaeger did not return the real Java/Python runtime trace; last="
                        + last);
    }

    private static boolean hasRuntimeCore(JsonNode response) {
        if (!response.path("data").isArray() || response.path("data").isEmpty()) {
            return false;
        }
        JsonNode data = response.path("data").get(0);
        Set<String> services = new HashSet<>();
        data.path("processes").forEach(process ->
                services.add(process.path("serviceName").asText()));
        Set<String> names = new HashSet<>();
        data.path("spans").forEach(span ->
                names.add(span.path("operationName").asText()));
        return services.containsAll(Set.of("reagent", "agent-capabilities"))
                && names.containsAll(Set.of(
                "agent.recovery",
                "rag.search",
                "mcp.create_ticket",
                "ticket.insert_or_read"));
    }

    private static GenericContainer<?> startJaeger() {
        return FailureSafeTraceInfrastructure.startOwned(
                () -> new GenericContainer<>("jaegertracing/all-in-one:1.62.0")
                        .withEnv("COLLECTOR_OTLP_ENABLED", "true")
                        .withExposedPorts(16686, 4318)
                        .waitingFor(Wait.forHttp("/")
                                .forPort(16686)
                                .withStartupTimeout(Duration.ofMinutes(2))),
                container -> container.start(),
                container -> container.stop());
    }

    private static FailureSafeTraceInfrastructure<
            GenericContainer<?>, PythonRuntime> startTraceInfrastructure() {
        return FailureSafeTraceInfrastructure.start(
                CrossServiceTraceIT::startJaeger,
                CrossServiceTraceIT::launchPython,
                (jaeger, python) -> awaitPythonReady(
                        python,
                        Duration.ofMinutes(4)),
                PythonRuntime::close,
                GenericContainer::stop);
    }

    private static PythonRuntime launchPython(GenericContainer<?> jaeger) {
        try {
            int port = availablePort();
            Path repositoryRoot = Path.of("").toAbsolutePath().normalize();
            Path serviceRoot = repositoryRoot.resolve(
                    "services/agent-capabilities");
            ProcessBuilder builder = new ProcessBuilder(
                    "uv",
                    "run",
                    "uvicorn",
                    "agent_capabilities.app:create_app",
                    "--factory",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    Integer.toString(port),
                    "--no-access-log");
            builder.directory(serviceRoot.toFile());
            builder.redirectErrorStream(true);
            Path runtimeLog = repositoryRoot.resolve(
                    "target/task12-python-runtime.log");
            Files.createDirectories(runtimeLog.getParent());
            builder.redirectOutput(ProcessBuilder.Redirect.to(runtimeLog.toFile()));
            Map<String, String> environment = builder.environment();
            environment.put("PYTHONDONTWRITEBYTECODE", "1");
            environment.put("UV_OFFLINE", "1");
            environment.put("AGENT_CAPABILITIES_ENV", "integration");
            environment.put(
                    "AGENT_CAPABILITIES_MYSQL_URL",
                    SHARED_CAPABILITIES.mappedMysqlUrl());
            environment.put(
                    "AGENT_CAPABILITIES_REDIS_URL",
                    SHARED_CAPABILITIES.mappedRedisUrl());
            environment.put(
                    "AGENT_CAPABILITIES_KNOWLEDGE_ROOT",
                    repositoryRoot.resolve("knowledge").toString());
            environment.put(
                    "AGENT_CAPABILITIES_OTLP_ENDPOINT",
                    otlpEndpoint(jaeger));
            environment.put(
                    "AGENT_CAPABILITIES_ACCEPTANCE_ENABLED",
                    "true");
            environment.put(
                    "AGENT_CAPABILITIES_CHAOS_ENABLED",
                    "true");
            environment.put(
                    "HF_HUB_CACHE",
                    repositoryRoot.resolve(
                            ".superpowers/sdd/hf-cache-task9").toString());
            environment.put("HF_HUB_OFFLINE", "1");
            environment.put("TRANSFORMERS_OFFLINE", "1");

            URI baseUri = URI.create("http://127.0.0.1:" + port);
            Process process = builder.start();
            return new PythonRuntime(process, baseUri, runtimeLog);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Cannot start current-source Python runtime", failure);
        }
    }

    private static void awaitPythonReady(
            PythonRuntime python,
            Duration timeout
    ) {
        Process process = python.process();
        URI baseUri = python.baseUri();
        Path runtimeLog = python.runtimeLog();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "Current-source Python runtime exited with "
                                + process.exitValue() + "; log="
                                + boundedLog(runtimeLog));
            }
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(
                                        baseUri.resolve("/internal/readiness"))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200
                        && response.body().contains("\"ready\":true")) {
                    useCapabilityBaseUri(baseUri);
                    return;
                }
            } catch (IOException ignored) {
                // Uvicorn or its lifespan is still starting.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while awaiting current-source Python",
                        interrupted);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while awaiting current-source Python",
                        interrupted);
            }
        }
        throw new IllegalStateException(
                "Current-source Python runtime did not become ready; log="
                        + boundedLog(runtimeLog));
    }

    private static String boundedLog(Path runtimeLog) {
        try {
            String log = Files.readString(runtimeLog, StandardCharsets.UTF_8);
            int start = Math.max(0, log.length() - 8_192);
            return log.substring(start);
        } catch (IOException failure) {
            return "<unavailable>";
        }
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket =
                     new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String otlpEndpoint() {
        return otlpEndpoint(JAEGER);
    }

    private static String otlpEndpoint(GenericContainer<?> jaeger) {
        return "http://" + jaeger.getHost() + ":"
                + jaeger.getMappedPort(4318) + "/v1/traces";
    }

    private static String jaegerQueryBase() {
        return "http://" + JAEGER.getHost() + ":"
                + JAEGER.getMappedPort(16686);
    }

    private record PythonRuntime(Process process, URI baseUri, Path runtimeLog) {
        private void close() {
            process.destroy();
            try {
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
