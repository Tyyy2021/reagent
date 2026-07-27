package com.reagent.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.Context;
import com.reagent.core.Decision;
import com.reagent.core.ToolCall;
import com.reagent.llm.LlmClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "reagent.llm", name = "mode", havingValue = "scripted")
public final class ScriptedIncidentLlmClient implements LlmClient {

    private static final Set<String> INCIDENT_TOOLS =
            Set.of("search_knowledge", "query_metrics", "search_logs", "create_ticket");
    private static final Pattern EXTERNAL_ALERT =
            Pattern.compile("(?m)^externalAlertId: ([^\\r\\n]{1,128})$");
    private static final Pattern TICKET_ID = Pattern.compile("^OPS-[A-Z0-9]{12}$");

    private final ObjectMapper mapper;
    private final Map<Context, Integer> consumedSizes =
            Collections.synchronizedMap(new WeakHashMap<>());

    public ScriptedIncidentLlmClient(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Decision chat(Context context, List<Map<String, Object>> toolSpecs) {
        return next(context, toolSpecs);
    }

    @Override
    public Decision chatStream(
            Context context,
            List<Map<String, Object>> toolSpecs,
            Consumer<String> onToken
    ) {
        Objects.requireNonNull(onToken, "onToken");
        return next(context, toolSpecs);
    }

    private Decision next(Context context, List<Map<String, Object>> toolSpecs) {
        Objects.requireNonNull(context, "context");
        requireExactCatalog(toolSpecs);
        List<Map<String, Object>> messages = context.messageSnapshot();
        synchronized (consumedSizes) {
            Integer priorSize = consumedSizes.put(context, messages.size());
            if (priorSize != null && priorSize == messages.size()) {
                throw new IllegalStateException("Unexpected extra incident LLM turn");
            }
        }
        String externalAlertId = externalAlertId(messages);
        CallIds ids = CallIds.forAlert(externalAlertId);
        List<Object> roles = messages.stream().map(message -> message.get("role")).toList();
        if (roles.equals(List.of("system", "user"))) {
            return knowledgeDecision(ids);
        }
        if (roles.equals(List.of("system", "user", "assistant", "tool"))) {
            return observationsDecision(messages, ids);
        }
        if (roles.equals(List.of(
                "system", "user", "assistant", "tool",
                "assistant", "tool", "tool"))) {
            return ticketDecision(messages, ids);
        }
        if (roles.equals(List.of(
                "system", "user", "assistant", "tool",
                "assistant", "tool", "tool", "assistant", "tool"))) {
            return finalDecision(messages, ids);
        }
        throw new IllegalStateException("Unexpected persisted incident turn shape");
    }

    private Decision knowledgeDecision(CallIds ids) {
        ToolCall call = new ToolCall(
                ids.knowledge(),
                "search_knowledge",
                "{\"query\":\"checkout connection pool exhaustion\",\"topK\":3}");
        return toolDecision(List.of(call));
    }

    private Decision observationsDecision(
            List<Map<String, Object>> messages,
            CallIds ids
    ) {
        JsonNode rag = parse(toolResult(messages, ids.knowledge()));
        if (!rag.path("hits").isArray() || rag.path("hits").isEmpty()
                || rag.path("hits").get(0).path("chunkId").asText().isBlank()) {
            throw new IllegalStateException("Persisted RAG result is invalid");
        }
        ToolCall metrics = new ToolCall(
                ids.metrics(),
                "query_metrics",
                "{\"service\":\"checkout\","
                        + "\"start\":\"2026-07-19T10:00:00Z\","
                        + "\"end\":\"2026-07-19T10:15:00Z\"}");
        ToolCall logs = new ToolCall(
                ids.logs(),
                "search_logs",
                "{\"service\":\"checkout\","
                        + "\"start\":\"2026-07-19T10:00:00Z\","
                        + "\"end\":\"2026-07-19T10:15:00Z\","
                        + "\"query\":\"SQLTransientConnectionException\","
                        + "\"limit\":2}");
        return toolDecision(List.of(metrics, logs));
    }

    private Decision ticketDecision(List<Map<String, Object>> messages, CallIds ids) {
        String chunkId = chunkId(messages, ids);
        JsonNode metrics = parse(toolResult(messages, ids.metrics()));
        JsonNode logs = parse(toolResult(messages, ids.logs()));
        if (Double.compare(metrics.path("errorRatePercent").asDouble(), 14.2) != 0
                || !logs.path("entries").isArray() || logs.path("entries").isEmpty()
                || !logs.path("entries").get(0).path("line").asText()
                .contains("Connection is not available")) {
            throw new IllegalStateException("Persisted incident observations are invalid");
        }
        ToolCall ticket = new ToolCall(
                ids.ticket(),
                "create_ticket",
                "{\"title\":\"Checkout connection pool exhausted\","
                        + "\"severity\":\"critical\","
                        + "\"evidence\":\"Citation " + jsonEscape(chunkId)
                        + "; errorRatePercent=14.2; "
                        + "SQLTransientConnectionException: Connection is not available\"}");
        return toolDecision(List.of(ticket));
    }

    private Decision finalDecision(List<Map<String, Object>> messages, CallIds ids) {
        String chunkId = chunkId(messages, ids);
        String result = toolResult(messages, ids.ticket());
        String answer;
        if (result.startsWith("{")) {
            String ticketId = parse(result).path("ticketId").asText();
            if (!TICKET_ID.matcher(ticketId).matches()) {
                throw new IllegalStateException("Persisted ticket result is invalid");
            }
            answer = "Checkout connection pool exhaustion confirmed; citation "
                    + chunkId
                    + "; error rate 14.2%; log: Connection is not available; "
                    + "approval APPROVED; ticket " + ticketId + ".";
        } else {
            if (!result.contains("Approval rejected")
                    || !result.contains("no remote action occurred")) {
                throw new IllegalStateException("Persisted approval result is invalid");
            }
            answer = "Checkout connection pool exhaustion confirmed; citation "
                    + chunkId
                    + "; error rate 14.2%; log: Connection is not available; "
                    + "approval REJECTED; no ticket was created.";
        }
        return Decision.finalAnswer(
                answer,
                Map.of("role", "assistant", "content", answer));
    }

    private String chunkId(List<Map<String, Object>> messages, CallIds ids) {
        String chunkId = parse(toolResult(messages, ids.knowledge()))
                .path("hits").path(0).path("chunkId").asText();
        if (chunkId.isBlank() || chunkId.length() > 128) {
            throw new IllegalStateException("Persisted citation is invalid");
        }
        return chunkId;
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Persisted tool result is invalid");
        }
    }

    private static String externalAlertId(List<Map<String, Object>> messages) {
        if (messages.size() < 2 || !"user".equals(messages.get(1).get("role"))) {
            throw new IllegalStateException("Persisted incident goal is missing");
        }
        Matcher matcher = EXTERNAL_ALERT.matcher(String.valueOf(messages.get(1).get("content")));
        if (!matcher.find()) {
            throw new IllegalStateException("Persisted external alert ID is missing");
        }
        return matcher.group(1);
    }

    private static String toolResult(List<Map<String, Object>> messages, String callId) {
        return messages.stream()
                .filter(message -> "tool".equals(message.get("role")))
                .filter(message -> callId.equals(message.get("tool_call_id")))
                .map(message -> String.valueOf(message.get("content")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Persisted tool result is missing"));
    }

    private static Decision toolDecision(List<ToolCall> calls) {
        return Decision.tools(assistantWithCalls(calls), calls);
    }

    private static Map<String, Object> assistantWithCalls(List<ToolCall> calls) {
        List<Map<String, Object>> rawCalls = calls.stream()
                .map(call -> Map.<String, Object>of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of(
                                "name", call.name(),
                                "arguments", call.arguments())))
                .toList();
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", rawCalls);
        return assistant;
    }

    private static void requireExactCatalog(List<Map<String, Object>> specs) {
        if (specs == null) {
            throw new IllegalStateException("Incident tool catalog is missing");
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> spec : specs) {
            if (spec == null || !"function".equals(spec.get("type"))
                    || !(spec.get("function") instanceof Map<?, ?> function)
                    || !(function.get("name") instanceof String name)
                    || name.isBlank()
                    || !(function.get("parameters") instanceof Map<?, ?>)) {
                throw new IllegalStateException("Incident tool catalog is invalid");
            }
            names.add(name);
        }
        if (names.size() != Set.copyOf(names).size()
                || !Set.copyOf(names).equals(INCIDENT_TOOLS)) {
            throw new IllegalStateException("Incident tool catalog does not match demo scenario");
        }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private record CallIds(String knowledge, String metrics, String logs, String ticket) {
        private static CallIds forAlert(String alert) {
            String suffix = digest(alert).substring(0, 16);
            return new CallIds(
                    "call-search-knowledge-" + suffix,
                    "call-query-metrics-" + suffix,
                    "call-search-logs-" + suffix,
                    "call-create-ticket-" + suffix);
        }
    }
}
