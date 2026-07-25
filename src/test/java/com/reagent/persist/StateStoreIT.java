package com.reagent.persist;

import com.reagent.core.Context;
import com.reagent.core.TaskRunToken;
import com.reagent.core.ToolCall;
import com.reagent.testsupport.InfrastructureIT;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateStoreIT extends InfrastructureIT {

    @Autowired
    private StateStore stateStore;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ToolCallRepository toolCallRepository;

    @Autowired
    private EntityManager entityManager;

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("malformedAssistantToolCalls")
    @Transactional
    void rejectsMalformedAssistantToolCallsWithoutPersistingMessageOrLedger(
            String caseName, Map<String, Object> rawAssistant) {
        TaskEntity task = stateStore.createTask(
                "reject malformed assistant " + caseName,
                "system prompt");
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        long messagesBefore = messageRepository.countByTaskId(task.getId());
        long ledgerBefore = toolCallRepository.count();

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> stateStore.appendAssistant(token, rawAssistant));

        assertEquals("Invalid assistant tool calls", error.getMessage());
        assertEquals(messagesBefore, messageRepository.countByTaskId(task.getId()));
        assertEquals(ledgerBefore, toolCallRepository.count());
    }

    @Test
    @Transactional
    void reconstructsToolCallContextAndLedgerFromMySql() {
        TaskEntity task = stateStore.createTask("inspect runtime", "system prompt");
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        ToolCall call = new ToolCall("call-state-store-it", "read_file", "{\"path\":\"README.md\"}");

        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of(Map.of(
                "id", call.id(),
                "type", "function",
                "function", Map.of("name", call.name(), "arguments", call.arguments()))));
        stateStore.appendAssistant(token, assistant);
        stateStore.markInProgress(token, call);
        stateStore.recordToolResult(token, call, "file contents");

        entityManager.flush();
        entityManager.clear();

        Context restored = stateStore.loadContext(task.getId());
        assertEquals(List.of("system", "user", "assistant", "tool"), restored.messages().stream()
                .map(message -> String.valueOf(message.get("role")))
                .toList());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> restoredCalls =
                (List<Map<String, Object>>) restored.messages().get(2).get("tool_calls");
        assertEquals(call.id(), restoredCalls.getFirst().get("id"));
        assertEquals(call.id(), restored.messages().get(3).get("tool_call_id"));
        assertEquals(ToolCallStatus.DONE, stateStore.statusOf(task.getId(), call.id()));
        assertEquals(List.of(0, 1, 2, 3), messageRepository.findByTaskIdOrderByIdAsc(task.getId()).stream()
                .map(MessageEntity::getSeq)
                .toList());
    }

    @Test
    @Transactional
    void persistsStructuredArgumentsAndAllowsFinalAssistantWithoutToolCalls() {
        TaskEntity task = stateStore.createTask("persist structured arguments", "system prompt");
        TaskRunToken token = stateStore.claim(task.getId()).orElseThrow();
        String callId = "call-structured-arguments";

        stateStore.appendAssistant(token, Map.of(
                "role", "assistant",
                "content", "final answer"));
        stateStore.appendAssistant(token, assistant(List.of(rawCall(
                true,
                callId,
                true,
                "read_file",
                Map.of("path", "README.md")))));

        entityManager.flush();
        entityManager.clear();

        assertEquals(
                "{\"path\":\"README.md\"}",
                toolCallRepository.findById(callId).orElseThrow().getArguments());
        assertEquals(
                List.of(new ToolCall(
                        callId,
                        "read_file",
                        "{\"path\":\"README.md\"}")),
                stateStore.loadContext(task.getId()).pendingToolCalls());
        assertEquals(
                Map.of("role", "assistant", "content", "final answer"),
                stateStore.loadContext(task.getId()).messages().get(2));
    }

    private static Stream<Arguments> malformedAssistantToolCalls() {
        return Stream.of(
                Arguments.of("missing id", assistant(List.of(rawCall(false, null, true, "read_file", "{}")))),
                Arguments.of("null id", assistant(List.of(rawCall(true, null, true, "read_file", "{}")))),
                Arguments.of("numeric id", assistant(List.of(rawCall(true, 123, true, "read_file", "{}")))),
                Arguments.of("boolean id", assistant(List.of(rawCall(true, true, true, "read_file", "{}")))),
                Arguments.of("list id", assistant(List.of(rawCall(true, List.of("call"), true, "read_file", "{}")))),
                Arguments.of("map id", assistant(List.of(rawCall(true, Map.of("id", "call"), true, "read_file", "{}")))),
                Arguments.of("missing name", assistant(List.of(rawCall(true, "call-invalid-name-missing", false, null, "{}")))),
                Arguments.of("null name", assistant(List.of(rawCall(true, "call-invalid-name-null", true, null, "{}")))),
                Arguments.of("numeric name", assistant(List.of(rawCall(true, "call-invalid-name-numeric", true, 123, "{}")))),
                Arguments.of("boolean name", assistant(List.of(rawCall(true, "call-invalid-name-boolean", true, true, "{}")))),
                Arguments.of("list name", assistant(List.of(rawCall(true, "call-invalid-name-list", true, List.of("read_file"), "{}")))),
                Arguments.of("map name", assistant(List.of(rawCall(true, "call-invalid-name-map", true, Map.of("name", "read_file"), "{}")))),
                Arguments.of("non-list tool calls", assistant("not-a-list")),
                Arguments.of("non-map list item", assistant(List.of("not-a-map"))),
                Arguments.of("missing function", assistant(List.of(Map.of("id", "call-missing-function", "type", "function")))),
                Arguments.of("non-map function", assistant(List.of(Map.of(
                        "id", "call-non-map-function", "type", "function", "function", "not-a-map")))));
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

    private static Map<String, Object> assistant(Object rawToolCalls) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", null);
        message.put("tool_calls", rawToolCalls);
        return message;
    }
}
