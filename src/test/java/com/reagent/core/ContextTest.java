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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextTest {

    @Test
    @SuppressWarnings("unchecked")
    void messageSnapshotIsADeepImmutableReadModelDetachedFromLiveContext() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", "read");
        function.put("arguments", "{}");
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("id", "call-1");
        call.put("type", "function");
        call.put("function", function);
        List<Map<String, Object>> calls = new ArrayList<>();
        calls.add(call);
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", calls);
        Context context = new Context("system");
        context.addAssistant(assistant);

        List<Map<String, Object>> snapshot = context.messageSnapshot();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(Map.of("role", "user")));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getLast().put("content", "changed"));
        List<Map<String, Object>> snapshotCalls =
                (List<Map<String, Object>>) snapshot.getLast().get("tool_calls");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshotCalls.add(Map.of()));
        Map<String, Object> snapshotFunction =
                (Map<String, Object>) snapshotCalls.getFirst().get("function");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshotFunction.put("name", "changed"));

        function.put("name", "mutated-live-source");
        assertEquals("read", snapshotFunction.get("name"));
        assertEquals("mutated-live-source",
                ((Map<String, Object>) ((List<Map<String, Object>>) context.messages().getLast()
                        .get("tool_calls")).getFirst().get("function")).get("name"));
    }

    @Test
    void pendingToolCallsRejectUnsafeLegacyIdentityBeforeExecution() {
        assertPendingIdentityRejected(
                "https://secret.example/CALL_SECRET_SENTINEL",
                "read_file",
                "Invalid tool call id",
                "CALL_SECRET_SENTINEL");
        assertPendingIdentityRejected(
                "call-safe",
                "SECRET/TOOL/NAME",
                "Invalid tool call name",
                "SECRET/TOOL/NAME");
    }

    @ParameterizedTest(name = "restored context rejects {0}")
    @MethodSource("invalidRestoredAssistantCalls")
    void pendingToolCallsRejectsMalformedRestoredAssistantToolCalls(
            String ignored, Map<String, Object> assistant) {
        Context context = new Context("system");
        context.addAssistant(assistant);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, context::pendingToolCalls);

        assertEquals("Invalid assistant tool calls", error.getMessage());
    }

    private static Stream<Arguments> invalidRestoredAssistantCalls() {
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
                Arguments.of("tool calls not list", assistantWithToolCalls(Map.of())),
                Arguments.of("tool call item not map", assistantWithToolCalls(List.of("not-a-map"))),
                Arguments.of("missing function", assistant(List.of(Map.of("id", "call-1", "type", "function")))),
                Arguments.of("function not map", assistant(List.of(Map.of("id", "call-1", "type", "function",
                        "function", "not-a-map")))));
    }

    @Test
    void pendingToolCallsRejectsNonStringToolResultCallId() {
        Context context = new Context("system");
        context.addAssistant(assistantWithCall("123", "read_file", "{}"));
        context.messages().add(Map.of(
                "role", "tool",
                "tool_call_id", 123,
                "content", "done"));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                context::pendingToolCalls);
        assertEquals("Invalid tool result call id", error.getMessage());
    }

    private static Map<String, Object> assistantWithCall(String id, String name, String arguments) {
        return assistant(List.of(rawCall(true, id, true, name, arguments)));
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

    private static Map<String, Object> assistantWithToolCalls(Object calls) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", calls);
        return message;
    }

    private static void assertPendingIdentityRejected(
            String id,
            String name,
            String expectedMessage,
            String sentinel) {
        Context context = new Context("system");
        context.addAssistant(Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "id", id,
                        "type", "function",
                        "function", Map.of(
                                "name", name,
                                "arguments", "{}")))));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, context::pendingToolCalls);

        assertEquals(expectedMessage, error.getMessage());
        assertFalse(error.getMessage().contains(sentinel));
    }
}
