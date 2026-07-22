package com.reagent.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.rag.KnowledgeVersionProvider;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProfileRegistryTest {

    @Test
    void omittedOrBlankProfileResolvesConfiguredCodingDefault() {
        AgentProfileRegistry registry = registry();

        TaskProfileSnapshot omitted = registry.snapshot(null);
        TaskProfileSnapshot blank = registry.snapshot("  \t");

        assertEquals(omitted, blank);
        assertEquals("coding", omitted.profileId());
        assertEquals("v1", omitted.profileVersion());
        assertEquals("configured coding prompt", omitted.systemPrompt());
        assertEquals(List.of("read_file", "list_dir", "write_file", "run_command", "sleep_ms"),
                omitted.tools().stream().map(ToolSnapshot::name).toList());
        assertTrue(omitted.tools().stream()
                .allMatch(tool -> tool.approvalPolicy() == ApprovalPolicy.NONE));
        assertEquals(List.of(), omitted.mcpServerIds());
    }

    @Test
    void incidentOpsResolvesWithFrozenKnowledgeToolWhileUnknownIdsFailClosed() {
        AgentProfileRegistry registry = registry();
        TaskProfileSnapshot incidentOps = registry.snapshot("incident-ops");

        assertAll(
                () -> assertEquals("incident-ops", incidentOps.profileId()),
                () -> assertEquals("intake-v1", incidentOps.profileVersion()),
                () -> assertEquals("incident-ops", incidentOps.knowledgeBaseId()),
                () -> assertEquals("v1-test", incidentOps.knowledgeIndexVersion()),
                () -> assertEquals(List.of("search_knowledge"),
                        incidentOps.tools().stream().map(ToolSnapshot::name).toList()),
                () -> assertEquals(List.of(), incidentOps.mcpServerIds()),
                () -> assertThrows(UnknownProfileException.class,
                        () -> registry.snapshot("does-not-exist"))
        );
    }

    @Test
    void activeVersionIsResolvedOnlyWhenCreatingAnIncidentSnapshot() {
        AtomicReference<String> currentVersion = new AtomicReference<>("v1-fixed");
        AtomicInteger calls = new AtomicInteger();
        KnowledgeVersionProvider provider = knowledgeBaseId -> {
            calls.incrementAndGet();
            assertEquals("incident-ops", knowledgeBaseId);
            return currentVersion.get();
        };
        AgentProfileRegistry registry = registry(provider);

        TaskProfileSnapshot frozen = registry.snapshot("incident-ops");
        currentVersion.set("v1-new");

        assertEquals("v1-fixed", frozen.knowledgeIndexVersion());
        assertEquals("v1-new", registry.snapshot("incident-ops").knowledgeIndexVersion());
        TaskProfileSnapshot coding = registry.snapshot("coding");
        assertEquals("configured coding prompt", coding.systemPrompt());
        assertEquals(null, coding.knowledgeIndexVersion());
        assertEquals(2, calls.get(), "coding profile must not consult active-version provider");
    }

    private static AgentProfileRegistry registry() {
        return registry(knowledgeBaseId -> "v1-test");
    }

    private static AgentProfileRegistry registry(KnowledgeVersionProvider provider) {
        List<String> names = List.of("read_file", "list_dir", "write_file", "run_command", "sleep_ms");
        List<Tool> tools = new ArrayList<>();
        for (String name : names) {
            tools.add(tool(name));
        }
        tools.add(tool("search_knowledge"));
        ObjectMapper mapper = new ObjectMapper();
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(tools), new SchemaHasher(mapper), new ToolProperties());

        AgentProfileProperties.Profile coding = new AgentProfileProperties.Profile();
        coding.setVersion("v1");
        coding.setSystemPrompt("configured coding prompt");
        coding.setToolNames(names);
        coding.setMcpServerIds(List.of());
        AgentProfileProperties.Profile incidentOps = new AgentProfileProperties.Profile();
        incidentOps.setVersion("intake-v1");
        incidentOps.setSystemPrompt("configured incident prompt");
        incidentOps.setKnowledgeBaseId("incident-ops");
        incidentOps.setKnowledgeIndexVersion("active");
        incidentOps.setToolNames(List.of("search_knowledge"));
        incidentOps.setMcpServerIds(List.of());
        AgentProfileProperties properties = new AgentProfileProperties();
        properties.setDefaultId("coding");
        properties.setDefinitions(new LinkedHashMap<>(Map.of(
                "coding", coding,
                "incident-ops", incidentOps)));
        return new AgentProfileRegistry(properties, resolver, provider);
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test " + name; }
            @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
            @Override public String execute(JsonNode args, ToolContext ctx) { return "ok"; }
        };
    }
}
