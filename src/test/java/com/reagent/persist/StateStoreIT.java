package com.reagent.persist;

import com.reagent.core.Context;
import com.reagent.core.TaskRunToken;
import com.reagent.core.ToolCall;
import com.reagent.testsupport.InfrastructureIT;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateStoreIT extends InfrastructureIT {

    @Autowired
    private StateStore stateStore;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private EntityManager entityManager;

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
}
