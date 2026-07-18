package com.reagent.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolCatalogResolverTest {

    @Test
    void rejectsDuplicateRegistryNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolRegistry(List.of(tool("duplicate"), tool("duplicate"))));
    }

    @Test
    void rejectsIllegalOrMissingToolNames() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ToolRegistry(List.of(tool(null)))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ToolRegistry(List.of(tool("")))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ToolRegistry(List.of(tool("tool name")))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ToolRegistry(List.of(tool("tool.name")))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ToolRegistry(List.of(tool("a".repeat(65)))))
        );
    }

    @Test
    void canonicalHashIgnoresObjectInsertionOrder() {
        Map<String, Object> firstProperties = new LinkedHashMap<>();
        firstProperties.put("path", Map.of("type", "string"));
        firstProperties.put("limit", Map.of("type", "integer"));
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("type", "object");
        first.put("properties", firstProperties);
        first.put("required", List.of("path", "limit"));

        Map<String, Object> secondProperties = new LinkedHashMap<>();
        secondProperties.put("limit", Map.of("type", "integer"));
        secondProperties.put("path", Map.of("type", "string"));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("required", List.of("path", "limit"));
        second.put("properties", secondProperties);
        second.put("type", "object");

        SchemaHasher hasher = new SchemaHasher(new ObjectMapper());
        assertEquals(hasher.hash(first), hasher.hash(second));
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotDefensivelyFreezesProfileAndNestedToolSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", new LinkedHashMap<>(Map.of("type", "string")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        List<String> toolNames = new java.util.ArrayList<>(List.of("listed"));
        List<String> servers = new java.util.ArrayList<>(List.of("server-1"));

        Tool listed = tool("listed", "persisted description", schema);
        ToolProperties propertiesConfig = new ToolProperties();
        propertiesConfig.setTimeoutMs(1234);
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(List.of(listed)), new SchemaHasher(new ObjectMapper()), propertiesConfig);
        AgentProfileDefinition definition = new AgentProfileDefinition(
                "coding", "v1", "system prompt", null, null, servers, toolNames);

        TaskProfileSnapshot snapshot = resolver.snapshot(definition);
        toolNames.add("late-tool");
        servers.add("server-2");
        properties.put("late", Map.of("type", "boolean"));

        ToolSnapshot frozen = snapshot.tools().getFirst();
        assertEquals(List.of("server-1"), snapshot.mcpServerIds());
        assertEquals(List.of("listed"), definition.toolNames());
        assertEquals("persisted description", frozen.description());
        assertEquals(1234, frozen.timeoutMs());
        assertEquals("local", frozen.provider());
        assertEquals(Map.of("path", Map.of("type", "string")), frozen.parameterSchema().get("properties"));
        assertAll(
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> snapshot.tools().add(frozen)),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> frozen.parameterSchema().put("x", "y")),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> ((Map<String, Object>) frozen.parameterSchema().get("properties")).put("x", "y"))
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void descriptionDriftKeepsHashAndPersistedLlmDescription() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path"));
        Tool oldTool = tool("listed", "old task description", schema);
        TaskProfileSnapshot snapshot = resolver(oldTool).snapshot(definition("listed"));

        Tool changedDescription = tool("listed", "new runtime description", schema);
        TaskToolCatalog catalog = resolver(changedDescription).resolve(snapshot);
        Map<String, Object> function = (Map<String, Object>) catalog.toOpenAiSpec()
                .getFirst().get("function");

        assertEquals(snapshot.tools().getFirst().schemaHash(),
                new SchemaHasher(new ObjectMapper()).hash(changedDescription.parameterSchema()));
        assertEquals("old task description", function.get("description"));
        assertSame(changedDescription, catalog.get("listed"));
    }

    @Test
    void schemaDriftChangesHashAndFailsOldTaskResolution() {
        Tool original = tool("listed", "description", Map.of("type", "object"));
        TaskProfileSnapshot snapshot = resolver(original).snapshot(definition("listed"));
        Tool changed = tool("listed", "description", Map.of(
                "type", "object", "required", List.of("path")));

        assertNotEquals(snapshot.tools().getFirst().schemaHash(),
                new SchemaHasher(new ObjectMapper()).hash(changed.parameterSchema()));
        assertThrows(ToolSchemaDriftException.class, () -> resolver(changed).resolve(snapshot));
    }

    @Test
    void persistedAllowlistControlsSpecsAndExecutionLookup() {
        Tool listed = tool("listed");
        Tool extra = tool("extra");
        ToolCatalogResolver resolver = resolver(listed, extra);
        TaskProfileSnapshot snapshot = resolver.snapshot(definition("listed"));

        TaskToolCatalog catalog = resolver.resolve(snapshot);

        assertEquals(1, catalog.toOpenAiSpec().size());
        assertEquals("listed", functionName(catalog.toOpenAiSpec().getFirst()));
        assertSame(listed, catalog.get("listed"));
        assertSame(snapshot.tools().getFirst(), catalog.snapshot("listed"));
        assertNull(catalog.get("extra"));
        assertNull(catalog.snapshot("extra"));
    }

    @Test
    void missingSnapshottedToolFailsResolution() {
        TaskProfileSnapshot snapshot = resolver(tool("listed")).snapshot(definition("listed"));

        assertThrows(ToolSchemaDriftException.class,
                () -> resolver(tool("different")).resolve(snapshot));
    }

    private static Tool tool(String name) {
        return tool(name, "test tool", Map.of("type", "object"));
    }

    private static Tool tool(String name, String description, Map<String, Object> schema) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> parameterSchema() { return schema; }
            @Override public String execute(JsonNode args, ToolContext ctx) { return "ok"; }
        };
    }

    private static ToolCatalogResolver resolver(Tool... tools) {
        return new ToolCatalogResolver(
                new ToolRegistry(List.of(tools)), new SchemaHasher(new ObjectMapper()), new ToolProperties());
    }

    private static AgentProfileDefinition definition(String... toolNames) {
        return new AgentProfileDefinition(
                "coding", "v1", "system prompt", null, null, List.of(), List.of(toolNames));
    }

    @SuppressWarnings("unchecked")
    private static String functionName(Map<String, Object> spec) {
        return String.valueOf(((Map<String, Object>) spec.get("function")).get("name"));
    }
}
