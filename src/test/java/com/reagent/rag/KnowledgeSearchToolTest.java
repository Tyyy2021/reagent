package com.reagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.TaskRunToken;
import com.reagent.persist.StateStore;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeSearchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesTheExactReadOnlyModelSchemaWithoutResolvingState() {
        Fixture fixture = fixture(new RagSearchResponse(1, "v1", List.of()));

        assertEquals("search_knowledge", fixture.tool.name());
        assertEquals(IdempotencyClass.READ_ONLY, fixture.tool.idempotency());
        assertEquals(ApprovalPolicy.NONE, fixture.tool.approvalPolicy());
        assertEquals(Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "query", Map.of("type", "string", "minLength", 1, "maxLength", 512),
                        "topK", Map.of("type", "integer", "minimum", 1, "maximum", 5)),
                "required", List.of("query")), fixture.tool.parameterSchema());
        verifyNoInteractions(fixture.stateProvider);
    }

    @Test
    void malformedArgumentsFailBeforeStateOrGatewayIo() throws Exception {
        Fixture fixture = fixture(new RagSearchResponse(1, "v1", List.of()));
        List<String> invalid = List.of(
                "{}",
                "{\"query\":1}",
                "{\"query\":\"\"}",
                "{\"query\":\"ok\",\"topK\":2.5}",
                "{\"query\":\"ok\",\"topK\":0}",
                "{\"query\":\"ok\",\"topK\":6}",
                "{\"query\":\"ok\",\"topK\":\"3\"}",
                "{\"query\":\"" + "\uD83D\uDE80".repeat(513) + "\"}",
                "{\"query\":\"ok\",\"knowledgeBaseId\":\"other\"}");

        for (String raw : invalid) {
            RagContractException error = assertThrows(RagContractException.class,
                    () -> fixture.tool.execute(mapper.readTree(raw), contextWithToken()));
            assertEquals("RAG_TOOL_ARGUMENTS", error.code());
        }
        verifyNoInteractions(fixture.stateProvider);
        assertEquals(0, fixture.gateway.calls.get());
    }

    @Test
    void missingRunTokenFailsBeforeStateOrGatewayIo() throws Exception {
        Fixture fixture = fixture(new RagSearchResponse(1, "v1", List.of()));
        ToolContext withoutToken = new ToolContext("task-1", Path.of("."));

        RagContractException error = assertThrows(RagContractException.class,
                () -> fixture.tool.execute(mapper.readTree("{\"query\":\"checkout\"}"), withoutToken));

        assertEquals("RAG_RUN_TOKEN_REQUIRED", error.code());
        verifyNoInteractions(fixture.stateProvider);
        assertEquals(0, fixture.gateway.calls.get());
    }

    @Test
    void omittedTopKDefaultsToThreeAndExplicitIntegralValueIsUsed() throws Exception {
        Fixture omitted = fixture(new RagSearchResponse(1, "v1", List.of()));

        JsonNode empty = mapper.readTree(omitted.tool.execute(
                mapper.readTree("{\"query\":\"checkout\"}"), contextWithToken()));

        assertTrue(empty.path("hits").isEmpty());
        assertEquals(3, omitted.gateway.lastRequest.topK());

        Fixture explicit = fixture(new RagSearchResponse(1, "v1", List.of()));
        explicit.tool.execute(mapper.readTree("{\"query\":\"checkout\",\"topK\":5}"), contextWithToken());
        assertEquals(5, explicit.gateway.lastRequest.topK());
    }

    @Test
    void gatewayTimeoutIsBoundedAndPublishesNoEvent() throws Exception {
        Fixture fixture = fixture(new RagContractException(
                "RAG_IO", "RAG service I/O failed after one retry"));

        RagContractException error = assertThrows(RagContractException.class,
                () -> fixture.tool.execute(mapper.readTree("{\"query\":\"checkout\"}"), contextWithToken()));

        assertEquals("RAG_IO", error.code());
        assertTrue(error.getMessage().length() <= 256);
        assertEquals(0, fixture.transport.tokenPublishes);
    }

    @Test
    void responseVersionMismatchIsRejectedBeforePublishing() throws Exception {
        Fixture fixture = fixture(new RagSearchResponse(1, "v2", List.of()));

        RagContractException error = assertThrows(RagContractException.class,
                () -> fixture.tool.execute(mapper.readTree("{\"query\":\"checkout\"}"), contextWithToken()));

        assertEquals("RAG_RESPONSE_MISMATCH", error.code());
        assertEquals(0, fixture.transport.tokenPublishes);
    }

    @Test
    void successfulCitationsUseFrozenProfileAndPublishOnlySafeFencedAttributes() throws Exception {
        RagHit hit = new RagHit(
                "checkout-pool", "Checkout runbook", "Pool exhaustion",
                "knowledge/incident-ops/runbooks/checkout.md", 0.91,
                "Inspect pending acquisitions and saturation.");
        Fixture fixture = fixture(new RagSearchResponse(1, "v1", List.of(hit)));
        TaskRunToken token = token();

        String json = fixture.tool.execute(
                mapper.readTree("{\"query\":\"secret incident text\",\"topK\":1}"),
                new ToolContext(token, Path.of(".")));

        JsonNode result = mapper.readTree(json);
        assertEquals(Set.of("contractVersion", "indexVersion", "hits"), result.properties()
                .stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals("checkout-pool", result.path("hits").path(0).path("chunkId").asText());
        assertEquals("incident-ops", fixture.gateway.lastRequest.knowledgeBaseId());
        assertEquals("v1", fixture.gateway.lastRequest.indexVersion());
        verify(fixture.stateStore).loadProfile(token);

        assertEquals(1, fixture.transport.tokenPublishes);
        assertEquals(token, fixture.transport.token);
        assertEquals(TaskEvent.Type.KNOWLEDGE_RETRIEVED, fixture.transport.type);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) fixture.transport.data;
        assertEquals(Set.of("taskId", "indexVersion", "hitCount", "chunkIds"), attributes.keySet());
        assertEquals("task-1", attributes.get("taskId"));
        assertEquals("v1", attributes.get("indexVersion"));
        assertEquals(1, attributes.get("hitCount"));
        assertEquals(List.of("checkout-pool"), attributes.get("chunkIds"));
        String eventJson = mapper.writeValueAsString(attributes);
        assertFalse(eventJson.contains("secret incident text"));
        assertFalse(eventJson.contains("Inspect pending"));
        assertFalse(eventJson.contains("Checkout runbook"));
    }

    private Fixture fixture(Object gatewayResult) {
        StateStore stateStore = mock(StateStore.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<StateStore> stateProvider = mock(ObjectProvider.class);
        when(stateProvider.getObject()).thenReturn(stateStore);
        when(stateStore.loadProfile(token())).thenReturn(snapshot());
        RecordingGateway gateway = new RecordingGateway(gatewayResult);
        RecordingTransport transport = new RecordingTransport();
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                stateProvider, gateway, transport, mapper);
        return new Fixture(tool, stateProvider, stateStore, gateway, transport);
    }

    private static TaskProfileSnapshot snapshot() {
        return new TaskProfileSnapshot(
                "incident-ops", "v1", "prompt", "hash",
                "incident-ops", "v1", List.of(), List.of());
    }

    private static ToolContext contextWithToken() {
        return new ToolContext(token(), Path.of("."));
    }

    private static TaskRunToken token() {
        return new TaskRunToken("task-1", "worker-1", 7L);
    }

    private record Fixture(
            KnowledgeSearchTool tool,
            ObjectProvider<StateStore> stateProvider,
            StateStore stateStore,
            RecordingGateway gateway,
            RecordingTransport transport
    ) {
    }

    private static final class RecordingGateway implements RagGateway {
        private final Object result;
        private final AtomicInteger calls = new AtomicInteger();
        private RagSearchRequest lastRequest;

        private RecordingGateway(Object result) {
            this.result = result;
        }

        @Override
        public String requireActiveVersion(String knowledgeBaseId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RagSearchResponse search(RagSearchRequest request) {
            calls.incrementAndGet();
            lastRequest = request;
            if (result instanceof RuntimeException exception) {
                throw exception;
            }
            return (RagSearchResponse) result;
        }
    }

    private static final class RecordingTransport implements StreamTransport {
        private int tokenPublishes;
        private TaskRunToken token;
        private TaskEvent.Type type;
        private Object data;

        @Override
        public TaskEvent publish(String taskId, TaskEvent.Type type, Object data) {
            throw new AssertionError("run-time event must use the token-fenced overload");
        }

        @Override
        public TaskEvent publish(TaskRunToken token, TaskEvent.Type type, Object data) {
            tokenPublishes++;
            this.token = token;
            this.type = type;
            this.data = data;
            return TaskEvent.of(token.taskId(), "1", type, data, java.time.Instant.EPOCH);
        }

        @Override
        public Subscription subscribeWithReplay(String taskId, String cursor, EventSink sink) {
            throw new UnsupportedOperationException();
        }
    }
}
