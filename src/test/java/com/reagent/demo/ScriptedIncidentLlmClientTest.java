package com.reagent.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.Context;
import com.reagent.core.Decision;
import com.reagent.core.ToolCall;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptedIncidentLlmClientTest {

    private static final Set<String> TOOLS =
            Set.of("search_knowledge", "query_metrics", "search_logs", "create_ticket");

    @Test
    void fourPersistedTurnsDeriveCitationAndTicketFromActualToolResults() {
        ScriptedIncidentLlmClient client = new ScriptedIncidentLlmClient(new ObjectMapper());
        Context context = incidentContext("alert-real-values");

        Decision knowledge = client.chat(context, toolSpecs(TOOLS));
        ToolCall knowledgeCall = onlyCall(knowledge);
        append(context, knowledge);
        context.addToolResult(
                knowledgeCall.id(),
                "{\"hits\":[{\"chunkId\":\"chunk-from-real-rag\"}]}");

        Decision observations = client.chat(context, toolSpecs(TOOLS));
        assertEquals(Set.of("query_metrics", "search_logs"),
                observations.getToolCalls().stream().map(ToolCall::name)
                        .collect(java.util.stream.Collectors.toSet()));
        append(context, observations);
        ToolCall metrics = call(observations, "query_metrics");
        ToolCall logs = call(observations, "search_logs");
        context.addToolResult(metrics.id(), "{\"errorRatePercent\":14.2}");
        context.addToolResult(logs.id(),
                "{\"entries\":[{\"line\":\"SQLTransientConnectionException: "
                        + "Connection is not available\"}]}");

        Decision ticket = client.chat(context, toolSpecs(TOOLS));
        ToolCall ticketCall = onlyCall(ticket);
        assertEquals("create_ticket", ticketCall.name());
        assertTrue(ticketCall.arguments().contains("chunk-from-real-rag"));
        assertFalse(ticketCall.arguments().contains("idempotency_key"));
        append(context, ticket);
        context.addToolResult(ticketCall.id(),
                "{\"ticketId\":\"OPS-ABCDEF012345\",\"deduplicated\":false}");

        Decision completed = client.chat(context, toolSpecs(TOOLS));

        assertTrue(completed.isFinal());
        assertTrue(completed.getAnswer().contains("chunk-from-real-rag"));
        assertTrue(completed.getAnswer().contains("OPS-ABCDEF012345"));
        assertThrows(IllegalStateException.class,
                () -> client.chat(context, toolSpecs(TOOLS)));
    }

    @Test
    void rejectResultProducesNoTicketClaim() {
        ScriptedIncidentLlmClient client = new ScriptedIncidentLlmClient(new ObjectMapper());
        Context context = contextAtTicketResult(
                client, "alert-rejected", "Approval rejected; no remote action occurred");

        Decision completed = client.chat(context, toolSpecs(TOOLS));

        assertTrue(completed.getAnswer().contains("approval REJECTED"));
        assertTrue(completed.getAnswer().contains("no ticket was created"));
    }

    @Test
    void exactCatalogAndPersistedTurnShapesAreRequired() {
        ScriptedIncidentLlmClient client = new ScriptedIncidentLlmClient(new ObjectMapper());

        assertThrows(IllegalStateException.class,
                () -> client.chat(
                        incidentContext("alert-wrong-catalog"),
                        toolSpecs(Set.of("search_knowledge"))));
        Context missingTurn = incidentContext("alert-missing-turn");
        missingTurn.addToolResult("call-unexpected", "{}");
        assertThrows(IllegalStateException.class,
                () -> client.chat(missingTurn, toolSpecs(TOOLS)));
    }

    @Test
    void callIdsAreStablePerAlertAndDifferentAcrossAlerts() {
        ScriptedIncidentLlmClient client = new ScriptedIncidentLlmClient(new ObjectMapper());

        String first = onlyCall(client.chat(
                incidentContext("alert-one"), toolSpecs(TOOLS))).id();
        String rerun = onlyCall(client.chat(
                incidentContext("alert-one"), toolSpecs(TOOLS))).id();
        String second = onlyCall(client.chat(
                incidentContext("alert-two"), toolSpecs(TOOLS))).id();

        assertEquals(first, rerun);
        assertNotEquals(first, second);
        assertTrue(first.matches("call-search-knowledge-[0-9a-f]{16}"));
    }

    @Test
    void scriptedModeRejectsNonDemoProfilesAtStartup() {
        DemoLlmProperties properties = new DemoLlmProperties();
        properties.setMode("scripted");

        assertThrows(IllegalStateException.class,
                () -> new DemoModeGuard(properties, new MockEnvironment())
                        .afterPropertiesSet());

        MockEnvironment demo = new MockEnvironment();
        demo.setActiveProfiles("demo-smoke");
        new DemoModeGuard(properties, demo).afterPropertiesSet();
    }

    private static Context contextAtTicketResult(
            ScriptedIncidentLlmClient client,
            String alertId,
            String result
    ) {
        Context context = incidentContext(alertId);
        Decision first = client.chat(context, toolSpecs(TOOLS));
        append(context, first);
        context.addToolResult(onlyCall(first).id(), "{\"hits\":[{\"chunkId\":\"chunk-r\"}]}");
        Decision second = client.chat(context, toolSpecs(TOOLS));
        append(context, second);
        context.addToolResult(call(second, "query_metrics").id(), "{\"errorRatePercent\":14.2}");
        context.addToolResult(call(second, "search_logs").id(),
                "{\"entries\":[{\"line\":\"Connection is not available\"}]}");
        Decision third = client.chat(context, toolSpecs(TOOLS));
        append(context, third);
        context.addToolResult(onlyCall(third).id(), result);
        return context;
    }

    private static Context incidentContext(String externalAlertId) {
        Context context = new Context("incident system");
        context.addUser("""
                Investigate the following incident alert.
                source: fake-alertmanager
                externalAlertId: %s
                service: checkout
                startedAt: 2026-07-19T10:00:00Z
                """.formatted(externalAlertId).strip());
        return context;
    }

    private static void append(Context context, Decision decision) {
        context.addAssistant(decision.getAssistantMessage());
    }

    private static ToolCall onlyCall(Decision decision) {
        assertEquals(1, decision.getToolCalls().size());
        return decision.getToolCalls().getFirst();
    }

    private static ToolCall call(Decision decision, String name) {
        return decision.getToolCalls().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<Map<String, Object>> toolSpecs(Set<String> names) {
        List<Map<String, Object>> specs = new ArrayList<>();
        for (String name : names) {
            specs.add(Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", name,
                            "parameters", Map.of("type", "object"))));
        }
        return specs;
    }
}
