package com.reagent.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DecisionTest {

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("invalidRawAssistantCalls")
    void rejectsInvalidRawAssistantToolCalls(String ignored, Map<String, Object> rawAssistant) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Decision.tools(rawAssistant, List.of(validCall())));

        assertEquals("Invalid assistant tool calls", error.getMessage());
    }

    private static Stream<Arguments> invalidRawAssistantCalls() {
        return Stream.of(
                Arguments.of("missing id", assistant(List.of(rawCall(false, null, true, "read_file", "{}")))),
                Arguments.of("null id", assistant(List.of(rawCall(true, null, true, "read_file", "{}")))),
                Arguments.of("numeric id", assistant(List.of(rawCall(true, 123, true, "read_file", "{}")))),
                Arguments.of("boolean id", assistant(List.of(rawCall(true, true, true, "read_file", "{}")))),
                Arguments.of("list id", assistant(List.of(rawCall(true, List.of("call"), true, "read_file", "{}")))),
                Arguments.of("map id", assistant(List.of(rawCall(true, Map.of("id", "call"), true, "read_file", "{}")))),
                Arguments.of("missing name", assistant(List.of(rawCall(true, "call-1", false, null, "{}")))),
                Arguments.of("null name", assistant(List.of(rawCall(true, "call-1", true, null, "{}")))),
                Arguments.of("numeric name", assistant(List.of(rawCall(true, "call-1", true, 123, "{}")))),
                Arguments.of("boolean name", assistant(List.of(rawCall(true, "call-1", true, true, "{}")))),
                Arguments.of("list name", assistant(List.of(rawCall(true, "call-1", true, List.of("read_file"), "{}")))),
                Arguments.of("map name", assistant(List.of(rawCall(true, "call-1", true, Map.of("name", "read_file"), "{}")))),
                Arguments.of("missing tool calls", assistantWithoutToolCalls()),
                Arguments.of("tool calls map", assistantWithToolCalls(Map.of())),
                Arguments.of("tool call item", assistantWithToolCalls(List.of("not-a-map"))),
                Arguments.of("missing function", assistant(List.of(Map.of("id", "call-1", "type", "function")))),
                Arguments.of("function not map", assistant(List.of(Map.of("id", "call-1", "type", "function",
                        "function", "not-a-map")))));
    }

    @ParameterizedTest(name = "rejects {0} mismatch")
    @MethodSource("rawExecutableMismatches")
    void rejectsRawExecutableMismatches(
            String ignored, Map<String, Object> rawAssistant, List<ToolCall> executableCalls) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Decision.tools(rawAssistant, executableCalls));

        assertEquals("Assistant tool calls do not match decision", error.getMessage());
    }

    private static Stream<Arguments> rawExecutableMismatches() {
        return Stream.of(
                Arguments.of("id", assistant(List.of(rawCall(true, "call-raw", true, "read_file", "{}"))),
                        List.of(new ToolCall("call-executable", "read_file", "{}"))),
                Arguments.of("name", assistant(List.of(rawCall(true, "call-1", true, "read_file", "{}"))),
                        List.of(new ToolCall("call-1", "list_dir", "{}"))),
                Arguments.of("normalized arguments", assistant(List.of(rawCall(true, "call-1", true, "read_file",
                        Map.of("path", "README.md")))),
                        List.of(new ToolCall("call-1", "read_file", "{\"path\":\"OTHER.md\"}"))),
                Arguments.of("call count", assistant(List.of(rawCall(true, "call-1", true, "read_file", "{}"))),
                        List.of(validCall(), new ToolCall("call-2", "read_file", "{}"))),
                Arguments.of("two call order", assistant(List.of(
                        rawCall(true, "call-1", true, "read_file", "{}"),
                        rawCall(true, "call-2", true, "list_dir", "{}"))),
                        List.of(new ToolCall("call-2", "list_dir", "{}"),
                                new ToolCall("call-1", "read_file", "{}"))));
    }

    @Test
    void rejectsDuplicateToolCallIds() {
        Map<String, Object> raw = assistant(List.of(
                rawCall(true, "call-duplicate", true, "read_file", "{}"),
                rawCall(true, "call-duplicate", true, "list_dir", "{}")));
        List<ToolCall> executable = List.of(
                new ToolCall("call-duplicate", "read_file", "{}"),
                new ToolCall("call-duplicate", "list_dir", "{}"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Decision.tools(raw, executable));

        assertEquals("Invalid assistant tool calls", error.getMessage());
    }

    @ParameterizedTest(name = "normalizes {0} arguments to an empty object")
    @MethodSource("emptyRawArguments")
    void defaultsMissingOrNullRawArgumentsToEmptyObject(String ignored, Map<String, Object> rawAssistant) {
        Decision decision = Decision.tools(rawAssistant, List.of(validCall()));

        assertEquals(List.of(validCall()), decision.getToolCalls());
    }

    @SuppressWarnings("unchecked")
    private static Stream<Arguments> emptyRawArguments() {
        Map<String, Object> missing = rawCall(true, "call-1", true, "read_file", null);
        ((Map<String, Object>) missing.get("function")).remove("arguments");
        return Stream.of(
                Arguments.of("missing", assistant(List.of(missing))),
                Arguments.of("null", assistant(List.of(rawCall(true, "call-1", true, "read_file", null)))));
    }

    @Test
    void normalizesStructuredArgumentsAndDefensivelyFreezesExecutableCalls() {
        Map<String, Object> raw = assistant(List.of(rawCall(
                true,
                "call-structured",
                true,
                "read_file",
                Map.of("path", "README.md"))));
        List<ToolCall> executable = new ArrayList<>(List.of(
                new ToolCall(
                        "call-structured",
                        "read_file",
                        "{\"path\":\"README.md\"}")));

        Decision decision = Decision.tools(raw, executable);
        executable.clear();

        assertEquals(
                List.of(new ToolCall(
                        "call-structured",
                        "read_file",
                        "{\"path\":\"README.md\"}")),
                decision.getToolCalls());
        assertThrows(
                UnsupportedOperationException.class,
                () -> decision.getToolCalls().add(
                        new ToolCall("call-other", "read_file", "{}")));
    }

    private static ToolCall validCall() {
        return new ToolCall("call-1", "read_file", "{}");
    }

    private static Map<String, Object> rawCall(
            boolean includeId,
            Object id,
            boolean includeName,
            Object name,
            Object arguments) {
        Map<String, Object> function = new LinkedHashMap<>();
        if (includeName) {
            function.put("name", name);
        }
        function.put("arguments", arguments);

        Map<String, Object> call = new LinkedHashMap<>();
        if (includeId) {
            call.put("id", id);
        }
        call.put("type", "function");
        call.put("function", function);
        return call;
    }

    private static Map<String, Object> assistant(List<Map<String, Object>> calls) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", calls);
        return message;
    }

    private static Map<String, Object> assistantWithoutToolCalls() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        return message;
    }

    private static Map<String, Object> assistantWithToolCalls(Object calls) {
        Map<String, Object> message = assistantWithoutToolCalls();
        message.put("tool_calls", calls);
        return message;
    }
}
