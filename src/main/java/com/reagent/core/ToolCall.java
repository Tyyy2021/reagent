package com.reagent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模型发起的一次工具调用。
 *
 * @param id        本次调用的唯一 id(OpenAI 协议要求把它带回工具结果里)
 * @param name      工具名,如 read_file
 * @param arguments 字符串形式的 JSON 参数,如 {"path":"src/Main.java"}
 */
public record ToolCall(String id, String name, String arguments) {

    private static final String INVALID_ASSISTANT_TOOL_CALLS =
            "Invalid assistant tool calls";
    private static final ObjectMapper PROTOCOL_MAPPER = new ObjectMapper();
    private static final Pattern LEGAL_ID =
            Pattern.compile("^[A-Za-z0-9_-]{1,255}$");
    private static final Pattern LEGAL_NAME =
            Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public ToolCall {
        if (id == null || !LEGAL_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid tool call id");
        }
        if (name == null || !LEGAL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid tool call name");
        }
    }

    public static List<ToolCall> parseAssistantToolCalls(Object rawToolCalls) {
        if (!(rawToolCalls instanceof List<?> rawList)) {
            throw invalidAssistantToolCalls();
        }

        List<ToolCall> parsed = new ArrayList<>(rawList.size());
        Set<String> ids = new HashSet<>();
        for (Object rawCall : rawList) {
            if (!(rawCall instanceof Map<?, ?> call)
                    || !(call.get("id") instanceof String id)
                    || !(call.get("function") instanceof Map<?, ?> function)
                    || !(function.get("name") instanceof String name)) {
                throw invalidAssistantToolCalls();
            }

            ToolCall parsedCall = new ToolCall(
                    id,
                    name,
                    normalizeArguments(function.get("arguments")));
            if (!ids.add(parsedCall.id())) {
                throw invalidAssistantToolCalls();
            }
            parsed.add(parsedCall);
        }
        return List.copyOf(parsed);
    }

    private static String normalizeArguments(Object arguments) {
        if (arguments == null) {
            return "{}";
        }
        if (arguments instanceof String stringArguments) {
            return stringArguments;
        }
        try {
            return PROTOCOL_MAPPER.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            throw invalidAssistantToolCalls();
        }
    }

    private static IllegalArgumentException invalidAssistantToolCalls() {
        return new IllegalArgumentException(INVALID_ASSISTANT_TOOL_CALLS);
    }
}
