package com.reagent.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
