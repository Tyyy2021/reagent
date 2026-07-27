package com.reagent.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.TaskRunToken;
import com.reagent.mcp.McpGateway;
import com.reagent.mcp.McpRemoteTool;
import com.reagent.persist.MessageEntity;
import com.reagent.persist.MessageRepository;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskRepository;
import com.reagent.persist.ToolCallEntity;
import com.reagent.persist.ToolCallRepository;
import com.reagent.rag.KnowledgeSearchTool;
import com.reagent.rag.RagGateway;
import com.reagent.rag.RagSearchRequest;
import com.reagent.rag.RagSearchResponse;
import com.reagent.testsupport.InfrastructureIT;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@Import(TaskProfilePersistenceIT.RagTestConfiguration.class)
class TaskProfilePersistenceIT extends InfrastructureIT {

    private static final int MAX_SNAPSHOT_BYTES = 16_384;

    @DynamicPropertySource
    static void profileProperties(DynamicPropertyRegistry registry) {
        registry.add("reagent.profiles.max-snapshot-bytes", () -> MAX_SNAPSHOT_BYTES);
    }

    @Autowired private StateStore stateStore;
    @Autowired private ToolCatalogResolver resolver;
    @Autowired private List<Tool> runtimeTools;
    @Autowired private ToolProperties toolProperties;
    @Autowired private ObjectMapper mapper;
    @Autowired private TaskRepository taskRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ToolCallRepository toolCallRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AgentProfileRegistry profileRegistry;
    @Autowired private KnowledgeSearchTool knowledgeSearchTool;
    @Autowired private MutableRagGateway ragGateway;
    @MockBean private McpGateway mcpGateway;

    @BeforeEach
    void clearRuntimeRows() {
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM approval_request");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM task");
        ragGateway.reset("v1-a");
        reset(mcpGateway);
        when(mcpGateway.discover("fake-ops")).thenReturn(fakeOpsTools());
    }

    @Test
    void incidentRecoveryKeepsCreationTimeIndexAndCodingProfileUnchanged() throws Exception {
        TaskProfileSnapshot createdSnapshot = profileRegistry.snapshot("incident-ops");
        assertEquals("v1-a", createdSnapshot.knowledgeIndexVersion());
        assertEquals(
                List.of(
                        "search_knowledge",
                        "query_metrics",
                        "search_logs",
                        "create_ticket"),
                createdSnapshot.tools().stream().map(ToolSnapshot::name).toList());

        TaskEntity created = stateStore.createTask("investigate checkout pool exhaustion", createdSnapshot);
        ragGateway.activeVersion.set("v1-b");
        TaskRunToken token = stateStore.claim(created.getId()).orElseThrow();

        TaskProfileSnapshot recovered = stateStore.loadProfile(token);
        assertEquals("v1-a", recovered.knowledgeIndexVersion());
        String result = knowledgeSearchTool.execute(
                mapper.readTree("{\"query\":\"checkout pool exhaustion\"}"),
                new ToolContext(token, java.nio.file.Path.of(".")));

        assertEquals("v1-a", ragGateway.lastSearch.get().indexVersion());
        assertEquals(1, ragGateway.activeCalls.get(),
                "persist/load/recovery/tool execution must not resolve active again");
        assertTrue(mapper.readTree(result).path("hits").isEmpty());

        TaskProfileSnapshot coding = profileRegistry.snapshot("coding");
        assertEquals("coding", coding.profileId());
        assertNull(coding.knowledgeBaseId());
        assertNull(coding.knowledgeIndexVersion());
        assertEquals(List.of("read_file", "list_dir", "write_file", "run_command", "sleep_ms"),
                coding.tools().stream().map(ToolSnapshot::name).toList());
        assertEquals(1, ragGateway.activeCalls.get(), "coding must not consult the provider");
    }

    @Test
    void persistsReloadsAndResolvesTheExactTaskSnapshotAfterRestart() throws Exception {
        TaskProfileSnapshot expected = resolver.snapshot(codingDefinition("persisted system prompt"));

        TaskEntity created = stateStore.createTask("inspect snapshots", expected);
        String persistedJson = created.getProfileSnapshot();

        assertEquals("coding", created.getProfileId());
        assertNotNull(persistedJson);
        assertEquals(2, messageRepository.countByTaskId(created.getId()));
        assertEquals(List.of("persisted system prompt", "inspect snapshots"),
                messageRepository.findByTaskIdOrderByIdAsc(created.getId()).stream()
                        .map(MessageEntity::getContent)
                        .toList());

        entityManager.clear();
        TaskEntity reloadedTask = stateStore.getTask(created.getId());
        TaskProfileSnapshot reloaded = stateStore.loadProfile(created.getId());

        assertEquals(persistedJson, reloadedTask.getProfileSnapshot(),
                "The persisted snapshot bytes must not be regenerated on reload");
        assertEquals(mapper.readTree(mapper.writeValueAsString(expected)), mapper.readTree(persistedJson));
        assertEquals(expected, reloaded);

        ToolCatalogResolver restarted = new ToolCatalogResolver(
                new ToolRegistry(runtimeTools), new SchemaHasher(mapper.copy()), toolProperties);
        TaskToolCatalog catalog = restarted.resolve(reloaded);
        assertEquals(expected.tools().stream().map(ToolSnapshot::name).toList(),
                catalog.toOpenAiSpec().stream().map(TaskProfilePersistenceIT::functionName).toList());
    }

    @Test
    void schemaDriftFailsBeforeAnyLlmOrToolCall() {
        TaskProfileSnapshot snapshot = resolver.snapshot(codingDefinition("prompt"));
        TaskEntity task = stateStore.createTask("drift check", snapshot);
        TaskProfileSnapshot persisted = stateStore.loadProfile(task.getId());
        AtomicBoolean runtimeCalled = new AtomicBoolean();
        List<Tool> changedTools = runtimeTools.stream()
                .map(tool -> "read_file".equals(tool.name()) ? schemaChanged(tool, runtimeCalled) : tool)
                .toList();
        ToolCatalogResolver restarted = new ToolCatalogResolver(
                new ToolRegistry(changedTools), new SchemaHasher(mapper.copy()), toolProperties);

        assertThrows(ToolSchemaDriftException.class, () -> {
            restarted.resolve(persisted);
            runtimeCalled.set(true); // represents the first possible LLM/tool boundary after resolution
        });
        assertFalse(runtimeCalled.get());
    }

    @Test
    void everyAssistantLedgerRowCapturesItsAssistantMessageSequence() {
        TaskEntity task = stateStore.createTask("batch sequence", resolver.snapshot(codingDefinition("prompt")));
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        Map<String, Object> assistant = assistantWithCalls(
                call("call-seq-a", "read_file", "{\"path\":\"a\"}"),
                call("call-seq-b", "list_dir", "{\"path\":\".\"}"));

        int assistantSequence = stateStore.appendAssistant(token, assistant);
        entityManager.clear();

        assertEquals(2, assistantSequence);
        assertEquals(List.of(assistantSequence, assistantSequence),
                List.of(
                        toolCallRepository.findById("call-seq-a").orElseThrow().getAssistantMessageSeq(),
                        toolCallRepository.findById("call-seq-b").orElseThrow().getAssistantMessageSeq()));
        assertEquals(assistantSequence,
                messageRepository.findByTaskIdOrderByIdAsc(task.getId()).get(2).getSeq());
    }

    @Test
    void oversizedSnapshotIsRejectedBeforeTaskPersistence() {
        long before = taskRepository.count();
        TaskProfileSnapshot oversized = resolver.snapshot(codingDefinition("x".repeat(MAX_SNAPSHOT_BYTES * 2)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> stateStore.createTask("must not persist", oversized));

        assertTrue(error.getMessage().contains("snapshot"));
        assertEquals(before, taskRepository.count());
    }

    @Test
    void legacyNullSnapshotIsNotMaterializedWithoutRunToken() {
        jdbc.update("""
                INSERT INTO task (
                    id, goal, status, created_at, updated_at, recovery_count,
                    lease_epoch, profile_id, profile_snapshot
                ) VALUES (?, ?, 'RUNNING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, 0, 'coding', NULL)
                """, "legacy-readonly-profile-task", "legacy goal");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> stateStore.loadProfile("legacy-readonly-profile-task"));
        entityManager.clear();

        assertTrue(error.getMessage().contains("run token"));
        assertNull(stateStore.getTask("legacy-readonly-profile-task").getProfileSnapshot());
    }

    @Test
    void winningTokenMaterializesLegacyNullSnapshotBeforeRecovery() {
        jdbc.update("""
                INSERT INTO task (
                    id, goal, status, created_at, updated_at, recovery_count,
                    lease_epoch, profile_id, profile_snapshot
                ) VALUES (?, ?, 'RUNNING', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, 0, 'coding', NULL)
                """, "legacy-profile-task", "legacy goal");
        TaskRunToken token = stateStore.claim("legacy-profile-task").orElseThrow();

        TaskProfileSnapshot materialized = stateStore.loadProfile(token);
        entityManager.clear();
        TaskEntity reloaded = stateStore.getTask("legacy-profile-task");

        assertEquals("coding", materialized.profileId());
        assertNotNull(reloaded.getProfileSnapshot());
        assertEquals(materialized, mapper.convertValue(
                readTree(reloaded.getProfileSnapshot()), TaskProfileSnapshot.class));
    }

    private AgentProfileDefinition codingDefinition(String prompt) {
        return new AgentProfileDefinition(
                "coding", "v1", prompt, null, null, List.of(),
                List.of("read_file", "list_dir", "write_file", "run_command", "sleep_ms"));
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

    private static Tool schemaChanged(Tool delegate, AtomicBoolean executed) {
        return new Tool() {
            @Override public String name() { return delegate.name(); }
            @Override public String description() { return delegate.description(); }
            @Override public Map<String, Object> parameterSchema() {
                Map<String, Object> changed = new LinkedHashMap<>(delegate.parameterSchema());
                changed.put("task3_drift_probe", true);
                return changed;
            }
            @Override public String execute(JsonNode args, ToolContext ctx) throws Exception {
                executed.set(true);
                return delegate.execute(args, ctx);
            }
            @Override public com.reagent.tool.IdempotencyClass idempotency() { return delegate.idempotency(); }
            @Override public com.reagent.tool.ApprovalPolicy approvalPolicy() { return delegate.approvalPolicy(); }
        };
    }

    @SafeVarargs
    private static Map<String, Object> assistantWithCalls(Map<String, Object>... calls) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of(calls));
        return assistant;
    }

    private static Map<String, Object> call(String id, String name, String arguments) {
        return Map.of(
                "id", id,
                "type", "function",
                "function", Map.of("name", name, "arguments", arguments));
    }

    @SuppressWarnings("unchecked")
    private static String functionName(Map<String, Object> spec) {
        return String.valueOf(((Map<String, Object>) spec.get("function")).get("name"));
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @TestConfiguration
    static class RagTestConfiguration {
        @Bean
        @Primary
        MutableRagGateway mutableRagGateway() {
            return new MutableRagGateway();
        }
    }

    static final class MutableRagGateway implements RagGateway {
        private final AtomicReference<String> activeVersion = new AtomicReference<>("v1-a");
        private final AtomicInteger activeCalls = new AtomicInteger();
        private final AtomicReference<RagSearchRequest> lastSearch = new AtomicReference<>();

        void reset(String version) {
            activeVersion.set(version);
            activeCalls.set(0);
            lastSearch.set(null);
        }

        @Override
        public String requireActiveVersion(String knowledgeBaseId) {
            assertEquals("incident-ops", knowledgeBaseId);
            activeCalls.incrementAndGet();
            return activeVersion.get();
        }

        @Override
        public RagSearchResponse search(RagSearchRequest request) {
            lastSearch.set(request);
            return new RagSearchResponse(1, request.indexVersion(), List.of());
        }
    }
}
