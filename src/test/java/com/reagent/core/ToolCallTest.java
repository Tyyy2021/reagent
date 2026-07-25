package com.reagent.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ToolCallTest {

    @Test
    void acceptsExactIdentityBoundaries() {
        String id = "i".repeat(255);
        String name = "n".repeat(64);

        ToolCall call = new ToolCall(id, name, "{}");

        assertEquals(id, call.id());
        assertEquals(name, call.name());
    }

    @Test
    void rejectsUnsafeOrOversizedIdsWithoutEchoingInput() {
        String sentinel = "https://secret.example/token=CALL_SECRET_SENTINEL";
        List<String> invalid = Arrays.asList(
                null, "", " ", "call.with.dot", sentinel, "i".repeat(256));

        for (String id : invalid) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new ToolCall(id, "read_file", "{}"));
            assertEquals("Invalid tool call id", error.getMessage());
            assertFalse(error.getMessage().contains("CALL_SECRET_SENTINEL"));
        }
    }

    @Test
    void rejectsUnsafeOrOversizedNamesWithoutEchoingInput() {
        String sentinel = "SECRET/TOOL/URL";
        List<String> invalid = Arrays.asList(
                null, "", " ", "tool.with.dot", sentinel, "n".repeat(65));

        for (String name : invalid) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new ToolCall("call-safe", name, "{}"));
            assertEquals("Invalid tool call name", error.getMessage());
            assertFalse(error.getMessage().contains(sentinel));
        }
    }
}
