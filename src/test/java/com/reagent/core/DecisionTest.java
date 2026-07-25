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
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    @SuppressWarnings("unchecked")
    void deeplyDetachesAndFreezesToolAssistantMessage() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("path", "README.md");
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "read_file");
        function.put("arguments", arguments);
        Map<String, Object> rawCall = new LinkedHashMap<>();
        rawCall.put("id", "call-frozen");
        rawCall.put("type", "function");
        rawCall.put("function", function);
        List<Map<String, Object>> rawCalls =
                new ArrayList<>(List.of(rawCall));
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", rawCalls);
        ToolCall executable = new ToolCall(
                "call-frozen", "read_file", "{\"path\":\"README.md\"}");

        Decision decision = Decision.tools(
                assistant, List.of(executable));
        assistant.put("content", "mutated");
        arguments.put("path", "OTHER.md");
        function.put("name", "list_dir");
        rawCall.put("id", "call-mutated");
        rawCalls.clear();

        Map<String, Object> snapshot = decision.getAssistantMessage();
        assertEquals(null, snapshot.get("content"));
        assertEquals(
                List.of(executable),
                ToolCall.parseAssistantToolCalls(snapshot.get("tool_calls")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.put("content", "blocked"));
        List<Map<String, Object>> snapshotCalls =
                (List<Map<String, Object>>) snapshot.get("tool_calls");
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshotCalls.clear());
        Map<String, Object> snapshotFunction =
                (Map<String, Object>) snapshotCalls.getFirst().get("function");
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshotFunction.put("name", "blocked"));
        Map<String, Object> snapshotArguments =
                (Map<String, Object>) snapshotFunction.get("arguments");
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshotArguments.put("path", "blocked"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deeplyDetachesAndFreezesFinalAssistantMessage() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "model");
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", "done");
        assistant.put("metadata", metadata);

        Decision decision = Decision.finalAnswer("done", assistant);
        assistant.put("content", "mutated");
        metadata.put("source", "mutated");

        assertEquals("done", decision.getAssistantMessage().get("content"));
        Map<String, Object> snapshotMetadata =
                (Map<String, Object>) decision.getAssistantMessage().get("metadata");
        assertEquals("model", snapshotMetadata.get("source"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshotMetadata.put("source", "blocked"));
    }

    @ParameterizedTest(name = "rejects final assistant with {0}")
    @MethodSource("invalidFinalToolCalls")
    void rejectsInvalidOrNonEmptyFinalToolCalls(
            String ignored, Map<String, Object> assistant) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Decision.finalAnswer("done", assistant));

        assertEquals("Invalid final assistant message", error.getMessage());
    }

    private static Stream<Arguments> invalidFinalToolCalls() {
        return Stream.of(
                Arguments.of(
                        "malformed tool_calls",
                        assistantWithToolCalls(Map.of())),
                Arguments.of(
                        "non-empty tool_calls",
                        assistant(List.of(rawCall(
                                true,
                                "call-final",
                                true,
                                "read_file",
                                "{}")))));
    }

    @ParameterizedTest(name = "allows final assistant with {0}")
    @MethodSource("validFinalToolCalls")
    void allowsMissingNullOrEmptyFinalToolCalls(
            String ignored, Map<String, Object> assistant) {
        Decision decision = Decision.finalAnswer("done", assistant);

        assertTrue(decision.isFinal());
        assertEquals("done", decision.getAnswer());
    }

    private static Stream<Arguments> validFinalToolCalls() {
        Map<String, Object> explicitNull = assistantWithoutToolCalls();
        explicitNull.put("tool_calls", null);
        return Stream.of(
                Arguments.of("missing tool_calls", assistantWithoutToolCalls()),
                Arguments.of("null tool_calls", explicitNull),
                Arguments.of("empty tool_calls", assistant(List.of())));
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
