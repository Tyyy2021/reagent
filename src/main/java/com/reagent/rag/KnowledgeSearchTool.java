package com.reagent.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.reagent.core.TaskRunToken;
import com.reagent.persist.StateStore;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only knowledge search bound to the task's persisted profile snapshot. */
@Component
public class KnowledgeSearchTool implements Tool {

    private static final Set<String> ALLOWED_ARGUMENTS = Set.of("query", "topK");

    private final ObjectProvider<StateStore> stateStoreProvider;
    private final RagGateway gateway;
    private final StreamTransport events;
    private final ObjectWriter responseWriter;

    public KnowledgeSearchTool(ObjectProvider<StateStore> stateStoreProvider,
                               RagGateway gateway,
                               StreamTransport events,
                               ObjectMapper applicationMapper) {
        this.stateStoreProvider = stateStoreProvider;
        this.gateway = gateway;
        this.events = events;
        this.responseWriter = applicationMapper.copy().writerFor(RagSearchResponse.class);
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "Search the incident knowledge base frozen into this task and return bounded citations.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "query", Map.of("type", "string", "minLength", 1, "maxLength", 512),
                        "topK", Map.of("type", "integer", "minimum", 1, "maximum", 5)),
                "required", List.of("query"));
    }

    @Override
    public IdempotencyClass idempotency() {
        return IdempotencyClass.READ_ONLY;
    }

    @Override
    public ApprovalPolicy approvalPolicy() {
        return ApprovalPolicy.NONE;
    }

    @Override
    public String execute(JsonNode args, ToolContext context) {
        ParsedArguments parsed = parseArguments(args);
        TaskRunToken token = context.runToken().orElseThrow(() -> new RagContractException(
                "RAG_RUN_TOKEN_REQUIRED", "search_knowledge requires a claimed task run token"));

        StateStore stateStore = stateStoreProvider.getObject();
        TaskProfileSnapshot snapshot = stateStore.loadProfile(token);
        RagSearchRequest request = requestFrom(snapshot, parsed);
        RagSearchResponse response = gateway.search(request);
        if (response == null) {
            throw new RagContractException("RAG_RESPONSE_INVALID", "RAG gateway returned no response");
        }
        try {
            response.validateFor(request);
        } catch (IllegalArgumentException mismatch) {
            throw new RagContractException(
                    "RAG_RESPONSE_MISMATCH", "RAG search response does not match the frozen task profile");
        }

        String result;
        try {
            result = responseWriter.writeValueAsString(response);
        } catch (JsonProcessingException serializationFailure) {
            throw new RagContractException("RAG_RESPONSE_INVALID", "RAG response could not be serialized");
        }

        List<String> chunkIds = response.hits().stream().map(RagHit::chunkId).toList();
        events.publish(token, TaskEvent.Type.KNOWLEDGE_RETRIEVED, Map.of(
                "taskId", token.taskId(),
                "indexVersion", request.indexVersion(),
                "hitCount", response.hits().size(),
                "chunkIds", chunkIds));
        return result;
    }

    private static ParsedArguments parseArguments(JsonNode args) {
        if (args == null || !args.isObject()) {
            throw invalidArguments();
        }
        Set<String> fields = new HashSet<>();
        args.fieldNames().forEachRemaining(fields::add);
        if (!ALLOWED_ARGUMENTS.containsAll(fields)) {
            throw invalidArguments();
        }
        JsonNode queryNode = args.get("query");
        if (queryNode == null || !queryNode.isTextual()) {
            throw invalidArguments();
        }
        String query = queryNode.textValue();
        try {
            RagContract.requireCodePoints(query, 1, 512, "query");
        } catch (RuntimeException invalidQuery) {
            throw invalidArguments();
        }

        int topK = 3;
        JsonNode topKNode = args.get("topK");
        if (topKNode != null) {
            if (!topKNode.isIntegralNumber() || !topKNode.canConvertToInt()) {
                throw invalidArguments();
            }
            topK = topKNode.intValue();
            if (topK < 1 || topK > 5) {
                throw invalidArguments();
            }
        }
        return new ParsedArguments(query, topK);
    }

    private static RagSearchRequest requestFrom(TaskProfileSnapshot snapshot, ParsedArguments parsed) {
        if (snapshot == null
                || snapshot.knowledgeBaseId() == null
                || snapshot.knowledgeBaseId().isBlank()
                || snapshot.knowledgeIndexVersion() == null
                || snapshot.knowledgeIndexVersion().isBlank()
                || "active".equals(snapshot.knowledgeIndexVersion())) {
            throw new RagContractException(
                    "RAG_PROFILE_INVALID", "task profile does not contain a frozen knowledge index");
        }
        try {
            return new RagSearchRequest(
                    1,
                    snapshot.knowledgeBaseId(),
                    snapshot.knowledgeIndexVersion(),
                    parsed.query(),
                    parsed.topK());
        } catch (RuntimeException invalidProfile) {
            throw new RagContractException(
                    "RAG_PROFILE_INVALID", "task profile contains an invalid frozen knowledge index");
        }
    }

    private static RagContractException invalidArguments() {
        return new RagContractException(
                "RAG_TOOL_ARGUMENTS", "search_knowledge arguments do not match the tool schema");
    }

    private record ParsedArguments(String query, int topK) {
    }
}
