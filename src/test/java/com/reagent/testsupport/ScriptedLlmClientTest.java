package com.reagent.testsupport;

import com.reagent.core.Context;
import com.reagent.core.Decision;
import com.reagent.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptedLlmClientTest {

    @Test
    void consumesExpectedTurnWithExactToolSetAndPersistedContextPredicate() {
        Context context = new Context("system");
        context.addUser("goal");
        Decision expected = Decision.finalAnswer(
                "done", Map.of("role", "assistant", "content", "done"));
        AtomicBoolean predicateCalled = new AtomicBoolean();
        ScriptedLlmClient client = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of("alpha", "beta"), messages -> {
                    predicateCalled.set(true);
                    return messages.stream().map(message -> message.get("role")).toList()
                            .equals(List.of("system", "user"));
                }, expected)));

        Decision actual = client.chatStream(
                context, List.of(toolSpec("beta"), toolSpec("alpha")), token -> { });

        assertSame(expected, actual);
        assertTrue(predicateCalled.get());
        assertEquals(1, client.callCount());
        assertDoesNotThrow(client::assertExhausted);
    }

    @Test
    void extraAndMissingTurnsFailImmediatelyAndCallCountIncludesAttemptedCalls() {
        Context context = new Context("system");
        Decision decision = Decision.finalAnswer(
                "done", Map.of("role", "assistant", "content", "done"));
        ScriptedLlmClient extra = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of(), decision)));
        extra.chat(context, List.of());

        AssertionError extraError = assertThrows(AssertionError.class,
                () -> extra.chat(context, List.of()));
        assertTrue(extraError.getMessage().contains("extra"));
        assertEquals(2, extra.callCount());

        ScriptedLlmClient missing = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of(), decision),
                ScriptedLlmClient.turn(Set.of(), decision)));
        missing.chat(context, List.of());
        AssertionError missingError = assertThrows(AssertionError.class, missing::assertExhausted);
        assertTrue(missingError.getMessage().contains("1"));
        assertEquals(1, missing.callCount());
    }

    @Test
    void unexpectedToolSetMalformedSchemaAndRejectedContextFailTheCurrentTurn() {
        Context context = new Context("system");
        Decision decision = Decision.finalAnswer(
                "done", Map.of("role", "assistant", "content", "done"));
        ScriptedLlmClient wrongName = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of("allowed"), decision)));
        AssertionError nameError = assertThrows(AssertionError.class,
                () -> wrongName.chat(context, List.of(toolSpec("global-extra"))));
        assertTrue(nameError.getMessage().contains("allowed"));

        ScriptedLlmClient malformed = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of("allowed"), decision)));
        Map<String, Object> malformedSpec = new LinkedHashMap<>(toolSpec("allowed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> function = new LinkedHashMap<>((Map<String, Object>) malformedSpec.get("function"));
        function.remove("parameters");
        malformedSpec.put("function", function);
        AssertionError schemaError = assertThrows(AssertionError.class,
                () -> malformed.chat(context, List.of(malformedSpec)));
        assertTrue(schemaError.getMessage().contains("schema"));

        ScriptedLlmClient rejectedContext = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of(), messages -> false, decision)));
        AssertionError contextError = assertThrows(AssertionError.class,
                () -> rejectedContext.chat(context, List.of()));
        assertTrue(contextError.getMessage().contains("context"));
    }

    private static Map<String, Object> toolSpec(String name) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", "test " + name,
                        "parameters", Map.of("type", "object")));
    }
}
