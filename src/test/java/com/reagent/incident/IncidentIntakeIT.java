package com.reagent.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.AgentRunner;
import com.reagent.mcp.McpGateway;
import com.reagent.mcp.McpRemoteTool;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.rag.RagGateway;
import com.reagent.testsupport.InfrastructureIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IncidentIntakeIT extends InfrastructureIT {

    private static final int MAX_PAYLOAD_BYTES = 32 * 1024;

    @Autowired private IncidentIntakeService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @SpyBean private AgentProfileRegistry profiles;
    @MockBean private AgentRunner runner;
    @MockBean private RagGateway ragGateway;
    @MockBean private McpGateway mcpGateway;
    @LocalServerPort private int port;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @BeforeEach
    void clearDurableState() {
        jdbc.update("DELETE FROM incident_intake");
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM approval_request");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM task");
        reset(runner, ragGateway, mcpGateway);
        when(ragGateway.requireActiveVersion("incident-ops")).thenReturn("v1-test");
        when(mcpGateway.discover("fake-ops")).thenReturn(fakeOpsTools());
    }

    @Test
    void concurrentDuplicateReturnsOneTask() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<IncidentAccepted>> futures = IntStream.range(0, 8)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return service.accept(fixture());
                    }))
                    .toList();
            start.countDown();
            List<IncidentAccepted> results = new ArrayList<>();
            for (Future<IncidentAccepted> future : futures) {
                results.add(future.get());
            }

            assertEquals(1, results.stream().map(IncidentAccepted::taskId).distinct().count());
            assertEquals(1, results.stream().map(IncidentAccepted::incidentId).distinct().count());
            assertEquals(1, results.stream().filter(result -> !result.deduplicated()).count());
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from incident_intake", Integer.class));
            assertEquals(1, jdbc.queryForObject("select count(*) from task", Integer.class));
            assertEquals(2, jdbc.queryForObject("select count(*) from message", Integer.class),
                    "the unique-conflict losers must leave no orphan task messages");
            verify(runner, times(1)).resumeAsync(results.getFirst().taskId());
        }
    }

    @Test
    void freshStartsOnceAfterCommitAndDuplicateDoesNotRestart() {
        AtomicBoolean transactionActiveAtResume = new AtomicBoolean(true);
        doAnswer(invocation -> {
            transactionActiveAtResume.set(
                    TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, jdbc.queryForObject(
                    "select count(*) from incident_intake", Integer.class));
            return null;
        }).when(runner).resumeAsync(anyString());

        IncidentAccepted fresh = service.accept(fixture());
        IncidentAccepted duplicate = service.accept(fixture());

        assertFalse(transactionActiveAtResume.get(), "resumeAsync must run only after commit");
        assertFalse(fresh.deduplicated());
        assertTrue(duplicate.deduplicated());
        assertEquals(fresh.incidentId(), duplicate.incidentId());
        assertEquals(fresh.taskId(), duplicate.taskId());
        verify(runner, times(1)).resumeAsync(fresh.taskId());

        List<String> roles = jdbc.queryForList(
                "select role from message where task_id = ? order by seq", String.class, fresh.taskId());
        List<String> contents = jdbc.queryForList(
                "select content from message where task_id = ? order by seq", String.class,
                fresh.taskId());
        assertEquals(List.of("system", "user"), roles);
        assertFalse(contents.getFirst().contains(fixture().title()),
                "alert text must never be copied into the trusted system prompt");
        assertTrue(contents.getLast().contains(fixture().title()));
    }

    @Test
    void duplicateDoesNotDependOnCurrentProfileAvailability() {
        IncidentAccepted fresh = service.accept(fixture());
        doThrow(new IllegalStateException("profile provider unavailable"))
                .when(profiles).snapshot("incident-ops");

        IncidentAccepted duplicate = service.accept(fixture());

        assertTrue(duplicate.deduplicated());
        assertEquals(fresh.incidentId(), duplicate.incidentId());
        assertEquals(fresh.taskId(), duplicate.taskId());
        verify(runner, times(1)).resumeAsync(fresh.taskId());
    }

    @Test
    void controllerReturnsAcceptedThenStableDeduplicatedResponse() throws Exception {
        HttpResponse<String> freshResponse = postJson(fixture());
        HttpResponse<String> duplicateResponse = postJson(fixture());

        IncidentAccepted fresh = mapper.readValue(freshResponse.body(), IncidentAccepted.class);
        IncidentAccepted duplicate = mapper.readValue(
                duplicateResponse.body(), IncidentAccepted.class);
        assertEquals(202, freshResponse.statusCode());
        assertFalse(fresh.deduplicated());
        assertEquals(200, duplicateResponse.statusCode());
        assertTrue(duplicate.deduplicated());
        assertEquals(fresh.incidentId(), duplicate.incidentId());
        assertEquals(fresh.taskId(), duplicate.taskId());
    }

    @Test
    void unknownSourceAndOversizedLabelsFailClosed() throws Exception {
        HttpResponse<String> unknownSource = postJson(fixture(
                "unknown-alertmanager", "ALERT-CHECKOUT-UNKNOWN", Map.of("region", "cn-east")));

        LinkedHashMap<String, String> tooManyLabels = new LinkedHashMap<>();
        IntStream.range(0, 21).forEach(i -> tooManyLabels.put("label-" + i, "value"));
        HttpResponse<String> labelCount = postJson(fixture(
                "fake-alertmanager", "ALERT-CHECKOUT-LABEL-COUNT", tooManyLabels));
        HttpResponse<String> labelValue = postJson(fixture(
                "fake-alertmanager", "ALERT-CHECKOUT-LABEL-VALUE",
                Map.of("region", "x".repeat(257))));

        assertEquals(400, unknownSource.statusCode());
        assertEquals(400, labelCount.statusCode());
        assertEquals(400, labelValue.statusCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from task", Integer.class));
    }

    @Test
    void payloadLimitRejectsDeclaredAndChunkedOversizeBeforeJackson() throws Exception {
        byte[] oversized = ("{" + "x".repeat(MAX_PAYLOAD_BYTES))
                .getBytes(StandardCharsets.UTF_8);
        byte[] exactValidJson = ("{}" + " ".repeat(MAX_PAYLOAD_BYTES - 2))
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> declared = postBytes(oversized, false);
        HttpResponse<String> chunked = postBytes(oversized, true);
        HttpResponse<String> exact = postBytes(exactValidJson, false);

        assertEquals(MAX_PAYLOAD_BYTES + 1, oversized.length);
        assertEquals(MAX_PAYLOAD_BYTES, exactValidJson.length);
        assertEquals(413, declared.statusCode());
        assertEquals(413, chunked.statusCode());
        assertEquals(400, exact.statusCode(),
                "an exact-limit body must reach normal request validation, not the 413 branch");
    }

    @Test
    void payloadLimitRejectsMatrixParameterPathVariantBeforeJackson() throws Exception {
        byte[] oversized = ("{" + "x".repeat(MAX_PAYLOAD_BYTES))
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = postBytes(
                "/api/incidents;x=1", oversized, false);

        assertEquals(MAX_PAYLOAD_BYTES + 1, oversized.length);
        assertEquals(413, response.statusCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from task", Integer.class));
    }

    private HttpResponse<String> postJson(Object body) throws Exception {
        return postBytes(mapper.writeValueAsBytes(body), false);
    }

    private HttpResponse<String> postBytes(byte[] body, boolean chunked) throws Exception {
        return postBytes("/api/incidents", body, chunked);
    }

    private HttpResponse<String> postBytes(String path, byte[] body, boolean chunked)
            throws Exception {
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("content-type", "application/json")
                .POST(publisher)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static IncidentRequest fixture() {
        return fixture("fake-alertmanager", "ALERT-CHECKOUT-001", Map.of(
                "environment", "demo",
                "region", "cn-east"));
    }

    private static IncidentRequest fixture(
            String source,
            String externalAlertId,
            Map<String, String> labels
    ) {
        return new IncidentRequest(
                source,
                externalAlertId,
                "checkout",
                "critical",
                "Checkout error rate is above threshold",
                "5xx error rate exceeded 10% for five minutes",
                Instant.parse("2026-07-19T10:00:00Z"),
                labels);
    }

    private static List<McpRemoteTool> fakeOpsTools() {
        Map<String, Object> schema =
                Map.of("type", "object", "properties", Map.of());
        return List.of(
                new McpRemoteTool(
                        "fake-ops", "create_ticket", "Create a ticket", schema),
                new McpRemoteTool(
                        "fake-ops", "query_metrics", "Query metrics", schema),
                new McpRemoteTool(
                        "fake-ops", "search_logs", "Search logs", schema));
    }
}
