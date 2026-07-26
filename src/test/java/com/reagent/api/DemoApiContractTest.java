package com.reagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.reagent.acceptance.AcceptanceEvidence;
import com.reagent.approval.ApprovalService;
import com.reagent.approval.ApprovalStatus;
import com.reagent.approval.ApprovalView;
import com.reagent.core.AgentRunner;
import com.reagent.core.TaskControl;
import com.reagent.health.ComponentReadiness;
import com.reagent.health.ReAgentReadiness;
import com.reagent.incident.IncidentAccepted;
import com.reagent.incident.IncidentIntakeEntity;
import com.reagent.incident.IncidentIntakeRepository;
import com.reagent.incident.IncidentIntakeService;
import com.reagent.incident.IncidentRequest;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskStatus;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoApiContractTest {

    private static final Instant CREATED = Instant.parse("2026-07-26T01:02:03Z");
    private static final Instant UPDATED = Instant.parse("2026-07-26T01:03:04Z");

    private final ObjectMapper mapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private AgentRunner runner;

    private StateStore stateStore;

    private StreamTransport stream;

    private TaskControl taskControl;

    private ApprovalService approvals;

    private IncidentIntakeService incidentService;

    private IncidentIntakeRepository incidents;

    private MockMvc taskMvc;
    private MockMvc incidentMvc;
    private MockMvc approvalMvc;

    @BeforeEach
    void setUp() {
        runner = mock(AgentRunner.class);
        stateStore = mock(StateStore.class);
        stream = mock(StreamTransport.class);
        taskControl = mock(TaskControl.class);
        approvals = mock(ApprovalService.class);
        incidentService = mock(IncidentIntakeService.class);
        incidents = mock(IncidentIntakeRepository.class);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        taskMvc = MockMvcBuilders.standaloneSetup(new TaskController(
                        runner, stateStore, stream, taskControl, approvals,
                        incidents, mapper))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        incidentMvc = MockMvcBuilders.standaloneSetup(
                        new IncidentController(incidentService))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
        approvalMvc = MockMvcBuilders.standaloneSetup(
                        new ApprovalController(approvals))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
    }

    @Test
    void taskViewRestoresOnlyTheBoundedIncidentHeader() throws Exception {
        TaskEntity task = task(
                "task-console", TaskStatus.WAITING_APPROVAL,
                "TITLE_MUST_NOT_LEAK\nSUMMARY_MUST_NOT_LEAK\n"
                        + "secret=LABEL_MUST_NOT_LEAK",
                "", "incident-ops",
                1, "worker-a", 7);
        IncidentRequest request = new IncidentRequest(
                "prometheus",
                "ALERT-CHECKOUT-001",
                "checkout-api",
                "sev1",
                "TITLE_MUST_NOT_LEAK",
                "SUMMARY_MUST_NOT_LEAK",
                Instant.parse("2026-07-26T00:55:00Z"),
                Map.of("secret", "LABEL_MUST_NOT_LEAK"));
        IncidentIntakeEntity incident = IncidentIntakeEntity.create(
                "incident-console", request, "task-console", CREATED, mapper);
        when(stateStore.getTask("task-console")).thenReturn(task);
        when(incidents.findByTaskId("task-console")).thenReturn(Optional.of(incident));

        MvcResult result = taskMvc.perform(get("/api/tasks/task-console"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsByteArray());
        assertEquals(Set.of(
                        "taskId", "status", "goal", "result", "profile",
                        "recoveryCount", "ownerId", "leaseEpoch",
                        "createdAt", "updatedAt", "incident"),
                fieldNames(body));
        assertEquals("task-console", body.path("taskId").asText());
        assertEquals("WAITING_APPROVAL", body.path("status").asText());
        assertEquals("", body.path("goal").asText());
        assertEquals(1, body.path("recoveryCount").asInt());
        assertEquals("worker-a", body.path("ownerId").asText());
        assertEquals(7, body.path("leaseEpoch").asLong());
        assertEquals(CREATED.toString(), body.path("createdAt").asText());
        assertEquals(UPDATED.toString(), body.path("updatedAt").asText());

        JsonNode summary = body.path("incident");
        assertEquals(Set.of(
                        "source", "externalAlertId", "service", "severity", "startedAt"),
                fieldNames(summary));
        assertEquals("prometheus", summary.path("source").asText());
        assertEquals("ALERT-CHECKOUT-001", summary.path("externalAlertId").asText());
        assertEquals("checkout-api", summary.path("service").asText());
        assertEquals("sev1", summary.path("severity").asText());
        assertEquals("2026-07-26T00:55:00Z", summary.path("startedAt").asText());

        String json = result.getResponse().getContentAsString();
        assertFalse(json.contains("TITLE_MUST_NOT_LEAK"));
        assertFalse(json.contains("SUMMARY_MUST_NOT_LEAK"));
        assertFalse(json.contains("LABEL_MUST_NOT_LEAK"));
        assertFalse(json.contains("boundedPayloadJson"));
        assertFalse(json.contains("profileSnapshot"));
        assertFalse(json.contains("messages"));
        assertFalse(json.contains("arguments"));
    }

    @Test
    void codingTaskPreservesExistingFieldsAndUsesANullIncident() throws Exception {
        TaskEntity task = task(
                "task-coding", TaskStatus.COMPLETED,
                "inspect files", "done", "coding",
                0, null, 1);
        when(stateStore.getTask("task-coding")).thenReturn(task);
        when(incidents.findByTaskId("task-coding")).thenReturn(Optional.empty());

        taskMvc.perform(get("/api/tasks/task-coding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-coding"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.goal").value("inspect files"))
                .andExpect(jsonPath("$.result").value("done"))
                .andExpect(jsonPath("$.profile").value("coding"))
                .andExpect(jsonPath("$.incident").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void incidentSummaryIgnoresPersistedFieldsOutsideItsProjection()
            throws Exception {
        TaskEntity task = task(
                "task-projection", TaskStatus.RUNNING,
                "MUST_NOT_BE_RETURNED", "", "incident-ops",
                0, "worker-projection", 2);
        IncidentIntakeEntity incident = mock(IncidentIntakeEntity.class);
        when(incident.getSource()).thenReturn("fake-alertmanager");
        when(incident.getExternalAlertId()).thenReturn("ALERT-PROJECTION");
        when(incident.getBoundedPayloadJson()).thenReturn("""
                {
                  "service": "checkout",
                  "severity": "critical",
                  "startedAt": "2026-07-26T00:55:00Z",
                  "title": {"unexpected": "shape"},
                  "summary": ["not", "needed"],
                  "labels": null
                }
                """);
        when(stateStore.getTask("task-projection")).thenReturn(task);
        when(incidents.findByTaskId("task-projection"))
                .thenReturn(Optional.of(incident));

        taskMvc.perform(get("/api/tasks/task-projection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal").value(""))
                .andExpect(jsonPath("$.incident.service").value("checkout"))
                .andExpect(jsonPath("$.incident.severity").value("critical"))
                .andExpect(jsonPath("$.incident.startedAt")
                        .value("2026-07-26T00:55:00Z"));
    }

    @Test
    void sseQueryCursorRestoresReplayAndHeaderTakesPrecedence() throws Exception {
        TaskEntity streamed = task(
                "task-stream", TaskStatus.COMPLETED,
                "stream", "done", "incident-ops",
                0, null, 3);
        when(stateStore.getTask("task-stream")).thenReturn(streamed);
        completeEverySubscription();

        MvcResult query = taskMvc.perform(get("/api/tasks/task-stream/stream")
                        .queryParam("cursor", "query-7")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        taskMvc.perform(asyncDispatch(query))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "\"fromEventId\":\"query-7\"")));

        MvcResult header = taskMvc.perform(get("/api/tasks/task-stream/stream")
                        .queryParam("cursor", "query-ignored")
                        .header("Last-Event-ID", "header-9")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        taskMvc.perform(asyncDispatch(header))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "\"fromEventId\":\"header-9\"")));
    }

    @Test
    void incidentApprovalAndReadinessViewsRemainBounded() throws Exception {
        IncidentRequest request = fixedIncident("ALERT-HTTP-001");
        when(incidentService.accept(request))
                .thenReturn(new IncidentAccepted(
                        "incident-http", "task-http", false));
        when(approvals.list("task-http")).thenReturn(List.of(new ApprovalView(
                "task-http",
                "call-ticket",
                4,
                "create_ticket",
                ApprovalStatus.PENDING,
                "Create P1 ticket",
                "sev1",
                "checkout error rate 14.2%",
                null,
                CREATED,
                null)));
        ReAgentReadiness readiness = new ReAgentReadiness(
                true,
                UPDATED,
                List.of(new ComponentReadiness(
                        "mcp", true, "2.0.0", "ready")));

        incidentMvc.perform(post("/api/incidents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.incidentId").value("incident-http"))
                .andExpect(jsonPath("$.taskId").value("task-http"))
                .andExpect(jsonPath("$.deduplicated").value(false));
        approvalMvc.perform(get("/api/tasks/task-http/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toolCallId").value("call-ticket"))
                .andExpect(jsonPath("$[0].evidencePreview")
                        .value("checkout error rate 14.2%"))
                .andExpect(jsonPath("$[0].arguments").doesNotExist())
                .andExpect(jsonPath("$[0].argumentsSnapshot").doesNotExist())
                .andExpect(jsonPath("$[0].idempotencyKey").doesNotExist());
        JsonNode readinessJson = mapper.valueToTree(readiness);
        assertEquals(true, readinessJson.path("ready").asBoolean());
        assertEquals("mcp", readinessJson.path("components").path(0).path("name").asText());
        assertEquals(
                "2.0.0",
                readinessJson.path("components").path(0).path("version").asText());
    }

    @Test
    void demoAcceptanceViewKeepsOnlyBoundedEvidence() throws Exception {
        AcceptanceEvidence evidence = new AcceptanceEvidence(
                1,
                "task-http",
                "incident-ops",
                "COMPLETED",
                List.of("chunk-1"),
                List.of("runbooks/checkout.md#pool#abc123"),
                List.of("query_metrics", "search_logs", "create_ticket"),
                "APPROVED",
                List.of(1L, 2L),
                "OPS-0123456789AB",
                2,
                1,
                true);

        JsonNode body = mapper.valueToTree(evidence);
        assertEquals("task-http", body.path("taskId").asText());
        assertEquals(2, body.path("workerEpochs").path(1).asLong());
        assertEquals("OPS-0123456789AB", body.path("ticketId").asText());
        assertEquals(true, body.path("passed").asBoolean());
        assertFalse(body.has("messages"));
        assertFalse(body.has("arguments"));
        assertFalse(body.has("rawOutput"));
    }

    private void completeEverySubscription() {
        when(stream.subscribeWithReplay(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String taskId = invocation.getArgument(0);
                    StreamTransport.EventSink sink = invocation.getArgument(2);
                    sink.deliver(TaskEvent.of(
                            taskId,
                            "terminal-1",
                            TaskEvent.Type.COMPLETED,
                            Map.of("result", "done"),
                            UPDATED));
                    return (StreamTransport.Subscription) () -> {
                    };
                });
    }

    private static TaskEntity task(
            String id,
            TaskStatus status,
            String goal,
            String result,
            String profile,
            int recoveryCount,
            String ownerId,
            long leaseEpoch
    ) {
        TaskEntity task = mock(TaskEntity.class);
        when(task.getId()).thenReturn(id);
        when(task.getStatus()).thenReturn(status);
        when(task.getGoal()).thenReturn(goal);
        when(task.getResult()).thenReturn(result);
        when(task.getProfileId()).thenReturn(profile);
        when(task.getRecoveryCount()).thenReturn(recoveryCount);
        when(task.getOwnerId()).thenReturn(ownerId);
        when(task.getLeaseEpoch()).thenReturn(leaseEpoch);
        when(task.getCreatedAt()).thenReturn(CREATED);
        when(task.getUpdatedAt()).thenReturn(UPDATED);
        return task;
    }

    private static IncidentRequest fixedIncident(String externalAlertId) {
        return new IncidentRequest(
                "prometheus",
                externalAlertId,
                "checkout-api",
                "sev1",
                "Checkout error rate spike",
                "Error rate is 14.2% and the connection pool is exhausted.",
                Instant.parse("2026-07-26T00:55:00Z"),
                Map.of("environment", "demo"));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }
}
