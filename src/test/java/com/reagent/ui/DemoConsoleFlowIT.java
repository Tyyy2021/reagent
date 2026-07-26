package com.reagent.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.testsupport.IncidentScenarioFixture;
import com.reagent.testsupport.PythonCapabilitiesContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "reagent.streaming.transport=redis")
class DemoConsoleFlowIT extends IncidentScenarioFixture {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SSE_TIMEOUT = SCENARIO_TIMEOUT;
    private static final CurrentSourcePythonRuntime CURRENT_SOURCE_PYTHON =
            CurrentSourcePythonRuntime.start(
                    PythonCapabilitiesContainer.shared());

    static {
        useCapabilityBaseUri(CURRENT_SOURCE_PYTHON.baseUri());
    }

    @LocalServerPort
    private int consolePort;

    private final HttpClient browser = browserClient();

    @AfterAll
    static void stopCurrentSourcePython() {
        try {
            CURRENT_SOURCE_PYTHON.close();
        } finally {
            resetCapabilityBaseUri();
        }
    }

    @Test
    void happyPathServesConsoleInvestigatesApprovesAndReplaysToOneTicket() {
        assertStaticConsole();
        ScenarioHandle scenario = submitIncident("TASK13-CONSOLE-HAPPY");

        SseTape investigation = streamUntil(
                browser, scenario.taskId(), "0", "APPROVAL_REQUIRED");
        assertInvestigationEvidence(investigation);
        String approvalCursor = investigation.lastDurableId();

        JsonNode waitingTask = browserGet(
                browser, "/api/tasks/" + scenario.taskId(), 200);
        assertEquals("WAITING_APPROVAL", waitingTask.path("status").asText());
        assertIncidentHeader(waitingTask.path("incident"), "TASK13-CONSOLE-HAPPY");
        assertReady(browser);

        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        JsonNode approvals = browserGet(
                browser,
                "/api/tasks/" + scenario.taskId() + "/approvals",
                200);
        assertEquals(1, approvals.size());
        assertEquals("create_ticket", approvals.path(0).path("toolName").asText());
        assertFalse(approvals.path(0).has("arguments"));
        assertFalse(approvals.path(0).has("idempotencyKey"));
        String evidencePreview =
                approvals.path(0).path("evidencePreview").asText();
        assertTrue(evidencePreview.contains("errorRatePercent=14.2"));
        assertTrue(evidencePreview.contains("SQLTransientConnectionException"));
        assertTrue(evidencePreview.codePointCount(
                0, evidencePreview.length()) <= 512);

        decide(scenario, approval, "APPROVE");
        SseTape completion = streamUntil(
                browser, scenario.taskId(), approvalCursor, "COMPLETED");
        assertTrue(completion.hasType("TOOL_CALL"));
        assertTrue(completion.hasType("TOOL_RESULT"));
        assertTrue(completion.hasType("COMPLETED"));
        assertFalse(completion.durableIds().contains(approvalCursor));

        JsonNode completed = awaitCompleted(scenario);
        assertTrue(completed.path("result").asText().contains("ticket OPS-"));
        JsonNode acceptance = browserGet(
                browser,
                "/api/acceptance/tasks/" + scenario.taskId(),
                200);
        assertTrue(acceptance.path("passed").asBoolean());
        assertEquals("APPROVED", acceptance.path("approvalDecision").asText());
        assertEquals(1, acceptance.path("uniqueTicketCount").asInt());
        assertTrue(acceptance.path("ticketId").asText().startsWith("OPS-"));
    }

    @Test
    void rejectPathCompletesWithoutAnyRemoteTicket() {
        ScenarioHandle scenario = submitIncident("TASK13-CONSOLE-REJECT");
        SseTape investigation = streamUntil(
                browser, scenario.taskId(), "0", "APPROVAL_REQUIRED");
        ApprovalSnapshot approval = awaitPendingApproval(scenario);

        decide(scenario, approval, "REJECT");
        SseTape completion = streamUntil(
                browser,
                scenario.taskId(),
                investigation.lastDurableId(),
                "COMPLETED");
        assertTrue(completion.hasType("COMPLETED"));

        JsonNode completed = awaitCompleted(scenario);
        assertTrue(completed.path("result").asText()
                .contains("approval REJECTED"));
        assertTrue(completed.path("result").asText()
                .contains("no ticket was created"));

        JsonNode acceptance = browserGet(
                browser,
                "/api/acceptance/tasks/" + scenario.taskId(),
                200);
        assertTrue(acceptance.path("passed").asBoolean());
        assertEquals("REJECTED", acceptance.path("approvalDecision").asText());
        assertEquals("", acceptance.path("ticketId").asText());
        assertEquals(0, acceptance.path("createTicketAttempts").asInt());
        assertEquals(0, acceptance.path("uniqueTicketCount").asInt());
    }

    @Test
    void freshClientRestoresFromOnlyTaskIdAndCursorWithoutDuplicateEvents() {
        ScenarioHandle scenario = submitIncident("TASK13-CONSOLE-RESTORE");
        SseTape firstConnection = streamUntil(
                browser, scenario.taskId(), "0", "APPROVAL_REQUIRED");
        FreshBrowserState persisted = new FreshBrowserState(
                scenario.taskId(), firstConnection.lastDurableId());
        ApprovalSnapshot approval = awaitPendingApproval(scenario);
        decide(scenario, approval, "APPROVE");

        HttpClient freshBrowser = browserClient();
        JsonNode restoredTask = browserGet(
                freshBrowser, "/api/tasks/" + persisted.taskId(), 200);
        assertEquals(persisted.taskId(), restoredTask.path("taskId").asText());
        assertIncidentHeader(
                restoredTask.path("incident"), "TASK13-CONSOLE-RESTORE");
        JsonNode restoredApprovals = browserGet(
                freshBrowser,
                "/api/tasks/" + persisted.taskId() + "/approvals",
                200);
        assertEquals("APPROVED",
                restoredApprovals.path(0).path("status").asText());
        assertReady(freshBrowser);

        SseTape resumed = streamUntil(
                freshBrowser,
                persisted.taskId(),
                persisted.cursor(),
                "COMPLETED");
        Set<String> overlap = new LinkedHashSet<>(
                firstConnection.durableIds());
        overlap.retainAll(resumed.durableIds());
        assertTrue(overlap.isEmpty(),
                () -> "reconnect replayed durable IDs at/before cursor: " + overlap);
        assertTrue(resumed.hasType("COMPLETED"));

        awaitCompleted(scenario);
        JsonNode acceptance = browserGet(
                freshBrowser,
                "/api/acceptance/tasks/" + persisted.taskId(),
                200);
        assertTrue(acceptance.path("passed").asBoolean());
    }

    private void assertStaticConsole() {
        HttpResponse<String> index = browserText(browser, "/", 200);
        assertTrue(index.body().contains("id=\"incident-workspace\""));
        assertTrue(index.headers().firstValue("Content-Type")
                .orElse("").contains("text/html"));

        HttpResponse<String> styles =
                browserText(browser, "/styles.css", 200);
        assertTrue(styles.body().contains("--abyss: #08121b"));
        assertTrue(styles.headers().firstValue("Content-Type")
                .orElse("").contains("text/css"));

        HttpResponse<String> script =
                browserText(browser, "/app.js", 200);
        assertTrue(script.body().contains("new EventSource"));
        assertTrue(script.headers().firstValue("Content-Type")
                .orElse("").contains("javascript"));

        HttpResponse<String> favicon =
                browserText(browser, "/favicon.svg", 200);
        assertTrue(favicon.body().contains("<svg"));
        assertTrue(favicon.headers().firstValue("Content-Type")
                .orElse("").contains("image/svg+xml"));
    }

    private void assertReady(HttpClient client) {
        JsonNode readiness = browserGet(client, "/api/readiness", 200);
        assertTrue(
                readiness.path("ready").asBoolean(),
                () -> "Expected all readiness components to be ready: "
                        + readiness);
    }

    private static void assertInvestigationEvidence(SseTape tape) {
        assertTrue(tape.hasType("TASK_STARTED"));
        assertTrue(tape.hasType("KNOWLEDGE_RETRIEVED"));
        assertTrue(tape.hasToolEvent("TOOL_CALL", "query_metrics"));
        assertTrue(tape.hasToolEvent("TOOL_RESULT", "query_metrics"));
        assertTrue(tape.hasToolEvent("TOOL_CALL", "search_logs"));
        assertTrue(tape.hasToolEvent("TOOL_RESULT", "search_logs"));
        assertTrue(tape.hasType("APPROVAL_REQUIRED"));
        assertFalse(tape.firstData("KNOWLEDGE_RETRIEVED")
                .path("chunkIds").isEmpty());
        assertFalse(tape.lastDurableId().isBlank());
        assertEquals(
                tape.durableIds().size(),
                tape.events().stream()
                        .map(SseEvent::id)
                        .filter(id -> id != null && !id.isBlank())
                        .count(),
                "durable event IDs must be unique");
    }

    private static void assertIncidentHeader(
            JsonNode incident,
            String externalAlertId
    ) {
        assertNotNull(incident);
        assertEquals("fake-alertmanager", incident.path("source").asText());
        assertEquals(externalAlertId,
                incident.path("externalAlertId").asText());
        assertEquals("checkout", incident.path("service").asText());
        assertEquals("critical", incident.path("severity").asText());
        assertEquals("2026-07-19T10:00:00Z",
                incident.path("startedAt").asText());
        assertEquals(
                Set.of("source", "externalAlertId", "service", "severity", "startedAt"),
                fieldNames(incident));
    }

    private SseTape streamUntil(
            HttpClient client,
            String taskId,
            String cursor,
            String expectedType
    ) {
        String encodedCursor = URLEncoder.encode(
                cursor, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(applicationUri(
                        "/api/tasks/" + taskId + "/stream?cursor=" + encodedCursor))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        AtomicReference<InputStream> activeBody = new AtomicReference<>();
        AtomicReference<String> readerStage = new AtomicReference<>("not-started");
        List<String> observedTypes = new CopyOnWriteArrayList<>();
        ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor();
        Future<SseTape> future = reader.submit(
                () -> readSseUntil(
                        client,
                        request,
                        expectedType,
                        activeBody,
                        readerStage,
                        observedTypes));
        try {
            return future.get(
                    SSE_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("SSE request was interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof AssertionError assertion) {
                throw assertion;
            }
            throw new AssertionError("SSE reader failed", exception.getCause());
        } catch (TimeoutException exception) {
            closeQuietly(activeBody.get());
            future.cancel(true);
            throw new AssertionError(
                    "SSE did not reach " + expectedType + " within "
                            + SSE_TIMEOUT
                            + "; stage=" + readerStage.get()
                            + "; observed=" + observedTypes
                            + "; runtime=" + runtimeSnapshot(taskId),
                    exception);
        } finally {
            closeQuietly(activeBody.get());
            reader.shutdownNow();
        }
    }

    private String runtimeSnapshot(String taskId) {
        String status = stateStore.getTask(taskId).getStatus().name();
        List<String> persistedEvents = jdbc.queryForList(
                        "SELECT type FROM event WHERE task_id = ? ORDER BY id",
                        String.class,
                        taskId);
        Integer messages = jdbc.queryForObject(
                "SELECT COUNT(*) FROM message WHERE task_id = ?",
                Integer.class,
                taskId);
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_request WHERE task_id = ?",
                Integer.class,
                taskId);
        return "status=" + status
                + ", events=" + persistedEvents
                + ", messages=" + messages
                + ", approvals=" + approvals;
    }

    private SseTape readSseUntil(
            HttpClient client,
            HttpRequest request,
            String expectedType,
            AtomicReference<InputStream> activeBody,
            AtomicReference<String> readerStage,
            List<String> observedTypes
    ) {
        HttpResponse<InputStream> response;
        try {
            readerStage.set("sending-request");
            response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw new AssertionError("SSE request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("SSE request was interrupted", exception);
        }
        assertEquals(200, response.statusCode(),
                () -> "SSE returned " + response.statusCode());
        readerStage.set("headers-" + response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type")
                .orElse("").contains("text/event-stream"));

        InputStream body = response.body();
        activeBody.set(body);
        List<SseEvent> events = new ArrayList<>();
        String currentId = "";
        String currentType = "";
        StringBuilder currentData = new StringBuilder();
        try (body;
             BufferedReader lines = new BufferedReader(new InputStreamReader(
                     body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!currentType.isEmpty()) {
                        events.add(new SseEvent(
                                currentId,
                                currentType,
                                parseEventData(currentData.toString())));
                        observedTypes.add(currentType);
                        readerStage.set("event-" + currentType);
                        if (expectedType.equals(currentType)) {
                            return new SseTape(List.copyOf(events));
                        }
                    }
                    currentId = "";
                    currentType = "";
                    currentData.setLength(0);
                } else if (line.startsWith("id:")) {
                    currentId = fieldValue(line);
                } else if (line.startsWith("event:")) {
                    currentType = fieldValue(line);
                } else if (line.startsWith("data:")) {
                    if (!currentData.isEmpty()) currentData.append('\n');
                    currentData.append(fieldValue(line));
                }
            }
        } catch (IOException exception) {
            throw new AssertionError("Cannot read SSE response", exception);
        } finally {
            activeBody.compareAndSet(body, null);
        }
        if (!currentType.isEmpty()) {
            events.add(new SseEvent(
                    currentId,
                    currentType,
                    parseEventData(currentData.toString())));
        }
        throw new AssertionError(
                "SSE closed before " + expectedType + ": " + events);
    }

    private JsonNode parseEventData(String value) {
        try {
            return value.isBlank()
                    ? mapper.createObjectNode()
                    : mapper.readTree(value);
        } catch (IOException exception) {
            throw new AssertionError("SSE data is not valid JSON: " + value, exception);
        }
    }

    private JsonNode browserGet(
            HttpClient client,
            String path,
            int expectedStatus
    ) {
        HttpResponse<String> response = browserText(
                client, path, expectedStatus);
        try {
            return mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new AssertionError(
                    "Browser response is not JSON: " + path, exception);
        }
    }

    private HttpResponse<String> browserText(
            HttpClient client,
            String path,
            int expectedStatus
    ) {
        HttpRequest request = HttpRequest.newBuilder(applicationUri(path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString());
            assertEquals(
                    expectedStatus,
                    response.statusCode(),
                    () -> path + " returned " + response.statusCode()
                            + " body=" + response.body());
            return response;
        } catch (IOException exception) {
            throw new AssertionError("Browser request failed: " + path, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Browser request interrupted: " + path, exception);
        }
    }

    private URI applicationUri(String path) {
        return URI.create("http://127.0.0.1:" + consolePort).resolve(path);
    }

    private static HttpClient browserClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static String fieldValue(String line) {
        String value = line.substring(line.indexOf(':') + 1);
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private static void closeQuietly(InputStream body) {
        if (body == null) return;
        try {
            body.close();
        } catch (IOException ignored) {
            // Best-effort client disconnect after a bounded test read.
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> fields = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return Set.copyOf(fields);
    }

    private record FreshBrowserState(String taskId, String cursor) {
    }

    private record SseEvent(String id, String type, JsonNode data) {
    }

    private record SseTape(List<SseEvent> events) {

        boolean hasType(String type) {
            return events.stream().anyMatch(event -> type.equals(event.type()));
        }

        boolean hasToolEvent(String type, String toolName) {
            return events.stream().anyMatch(event ->
                    type.equals(event.type())
                            && toolName.equals(event.data().path("name").asText()));
        }

        JsonNode firstData(String type) {
            return events.stream()
                    .filter(event -> type.equals(event.type()))
                    .map(SseEvent::data)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Missing SSE event " + type));
        }

        Set<String> durableIds() {
            Set<String> ids = new LinkedHashSet<>();
            events.stream()
                    .map(SseEvent::id)
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(ids::add);
            return Set.copyOf(ids);
        }

        String lastDurableId() {
            for (int index = events.size() - 1; index >= 0; index -= 1) {
                String id = events.get(index).id();
                if (id != null && !id.isBlank()) return id;
            }
            throw new AssertionError("SSE tape contains no durable cursor");
        }
    }

    private record CurrentSourcePythonRuntime(
            Process process,
            URI baseUri,
            Path runtimeLog
    ) implements AutoCloseable {

        private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(4);

        private static CurrentSourcePythonRuntime start(
                PythonCapabilitiesContainer capabilities
        ) {
            Path repositoryRoot = Path.of("").toAbsolutePath().normalize();
            Path serviceRoot =
                    repositoryRoot.resolve("services/agent-capabilities");
            Path runtimeLog =
                    repositoryRoot.resolve("target/task13-python-runtime.log");
            Process process = null;
            try {
                int port = availablePort();
                Files.createDirectories(runtimeLog.getParent());
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
                builder.redirectOutput(
                        ProcessBuilder.Redirect.to(runtimeLog.toFile()));
                Map<String, String> environment = builder.environment();
                environment.put("PYTHONDONTWRITEBYTECODE", "1");
                environment.put("UV_OFFLINE", "1");
                environment.put("AGENT_CAPABILITIES_ENV", "integration");
                environment.put(
                        "AGENT_CAPABILITIES_MYSQL_URL",
                        capabilities.mappedMysqlUrl());
                environment.put(
                        "AGENT_CAPABILITIES_REDIS_URL",
                        capabilities.mappedRedisUrl());
                environment.put(
                        "AGENT_CAPABILITIES_KNOWLEDGE_ROOT",
                        repositoryRoot.resolve("knowledge").toString());
                environment.put(
                        "AGENT_CAPABILITIES_OTLP_ENDPOINT",
                        "http://127.0.0.1:9/v1/traces");
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
                process = builder.start();
                CurrentSourcePythonRuntime runtime =
                        new CurrentSourcePythonRuntime(
                                process, baseUri, runtimeLog);
                runtime.awaitReady(STARTUP_TIMEOUT);
                return runtime;
            } catch (IOException failure) {
                stop(process);
                throw new IllegalStateException(
                        "Cannot start current-source Python runtime", failure);
            } catch (RuntimeException | Error failure) {
                stop(process);
                throw failure;
            }
        }

        private void awaitReady(Duration timeout) {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(1))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            ObjectMapper mapper = new ObjectMapper();
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                if (!process.isAlive()) {
                    throw new IllegalStateException(
                            "Current-source Python runtime exited with "
                                    + process.exitValue()
                                    + "; log=" + boundedLog(runtimeLog));
                }
                try {
                    HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder(
                                            baseUri.resolve(
                                                    "/internal/readiness"))
                                    .timeout(Duration.ofSeconds(2))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        JsonNode readiness = mapper.readTree(response.body());
                        if (readiness.path("ready").asBoolean()
                                && "agent-capabilities".equals(
                                readiness.path("service").asText())
                                && "0.1.0".equals(
                                readiness.path("version").asText())) {
                            return;
                        }
                    }
                } catch (IOException ignored) {
                    // Uvicorn or its lifespan may still be starting.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while awaiting current-source Python");
                }
                pause();
            }
            throw new IllegalStateException(
                    "Current-source Python runtime did not become ready; log="
                            + boundedLog(runtimeLog));
        }

        @Override
        public void close() {
            stop(process);
        }

        private static int availablePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(
                    0, 0, InetAddress.getLoopbackAddress())) {
                return socket.getLocalPort();
            }
        }

        private static void pause() {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while awaiting current-source Python");
            }
        }

        private static String boundedLog(Path log) {
            try {
                String content = Files.readString(log, StandardCharsets.UTF_8);
                return content.substring(Math.max(0, content.length() - 8_192));
            } catch (IOException failure) {
                return "<unavailable>";
            }
        }

        private static void stop(Process candidate) {
            if (candidate == null) {
                return;
            }
            candidate.destroy();
            try {
                if (!candidate.waitFor(20, TimeUnit.SECONDS)) {
                    candidate.destroyForcibly();
                    candidate.waitFor(10, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                candidate.destroyForcibly();
            }
        }
    }
}
