package com.reagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.reagent.llm.LlmClient;
import com.reagent.persist.EventEntity;
import com.reagent.persist.EventRepository;
import com.reagent.persist.MessageEntity;
import com.reagent.persist.MessageRepository;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskStatus;
import com.reagent.persist.ToolCallRepository;
import com.reagent.persist.ToolCallStatus;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
import com.reagent.stream.TaskEventBus;
import com.reagent.testsupport.InfrastructureIT;
import com.reagent.testsupport.RecordingTool;
import com.reagent.testsupport.ScriptedLlmClient;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(AgentRunnerIT.TestBeans.class)
class AgentRunnerIT extends InfrastructureIT {

    @DynamicPropertySource
    static void runnerProperties(DynamicPropertyRegistry registry) {
        registry.add("reagent.worker.id", () -> "agent-runner-it");
        registry.add("reagent.profiles.definitions.coding.tool-names", () -> "recording");
    }

    @Autowired private AgentRunner runner;
    @Autowired private StateStore stateStore;
    @Autowired private AgentProfileRegistry profiles;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ToolCallRepository toolCallRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private StreamTransport transport;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SwitchableLlmClient llm;
    @Autowired @Qualifier("recordingTool") private RecordingTool recordingTool;
    @Autowired private GlobalExtraTool globalExtraTool;

    @BeforeEach
    void clearDurableRuntimeAndFakeState() {
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM task");
        llm.clear();
        globalExtraTool.reset();
        assertInstanceOf(TaskEventBus.class, transport);
    }

    @Test
    void runsToolThenCompletesAndRebuildsContext() {
        ToolCall call = new ToolCall("call-happy", recordingTool.name(), "{\"value\":1}");
        Decision tools = Decision.tools(assistantWithCall(call), List.of(call));
        Decision done = Decision.finalAnswer(
                "final answer", Map.of("role", "assistant", "content", "final answer"));
        ScriptedLlmClient script = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of(recordingTool.name()), messages ->
                        roles(messages).equals(List.of("system", "user")), tools),
                ScriptedLlmClient.turn(Set.of(recordingTool.name()), messages ->
                        roles(messages).equals(List.of("system", "user", "assistant", "tool"))
                                && call.id().equals(messages.getLast().get("tool_call_id"))
                                && "recorded-result".equals(messages.getLast().get("content")), done)));
        llm.use(script);
        int callsBefore = recordingTool.callCount();

        AgentRunner.RunResult result = runner.run("inspect runtime", "coding");

        script.assertExhausted();
        assertEquals(2, script.callCount());
        assertEquals("final answer", result.result());
        TaskEntity task = stateStore.getTask(result.taskId());
        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals("final answer", task.getResult());
        assertEquals(callsBefore + 1, recordingTool.callCount());
        assertEquals(result.taskId(), recordingTool.lastContext().orElseThrow().taskId());
        assertEquals(call.id(), recordingTool.lastContext().orElseThrow().idempotencyKey());
        assertEquals(ToolCallStatus.DONE,
                toolCallRepository.findById(call.id()).orElseThrow().getStatus());

        List<MessageEntity> messages = messageRepository.findByTaskIdOrderByIdAsc(result.taskId());
        assertEquals(List.of("system", "user", "assistant", "tool", "assistant"),
                messages.stream().map(MessageEntity::getRole).toList());
        assertEquals(List.of(0, 1, 2, 3, 4), messages.stream().map(MessageEntity::getSeq).toList());
        assertEquals(call.id(), messages.get(3).getToolCallId());
        assertEquals("recorded-result", messages.get(3).getContent());

        List<TaskEvent.Type> eventTypes = durableEvents(result.taskId()).stream()
                .map(event -> TaskEvent.Type.valueOf(event.getType()))
                .toList();
        assertEquals(List.of(
                        TaskEvent.Type.TASK_STARTED,
                        TaskEvent.Type.STEP,
                        TaskEvent.Type.TOOL_CALL,
                        TaskEvent.Type.TOOL_RESULT,
                        TaskEvent.Type.STEP,
                        TaskEvent.Type.COMPLETED),
                eventTypes);
    }

    @Test
    void unknownToolCannotExecuteThroughRecovery() {
        TaskProfileSnapshot frozen = profiles.snapshot("coding");
        assertEquals(List.of(recordingTool.name()), frozen.tools().stream().map(tool -> tool.name()).toList());
        TaskEntity task = stateStore.createTask("recover unknown call", frozen);
        TaskRunToken persistenceToken = stateStore.claim(task.getId()).orElseThrow();
        ToolCall injected = new ToolCall("call-global-extra", globalExtraTool.name(), "{}");
        stateStore.appendAssistant(persistenceToken, assistantWithCall(injected));
        Decision done = Decision.finalAnswer(
                "handled catalog error",
                Map.of("role", "assistant", "content", "handled catalog error"));
        ScriptedLlmClient script = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of(recordingTool.name()), messages ->
                        roles(messages).equals(List.of("system", "user", "assistant", "tool"))
                                && String.valueOf(messages.getLast().get("content"))
                                .contains("不存在名为 'global-extra' 的工具"), done)));
        llm.use(script);
        int allowedCallsBefore = recordingTool.callCount();

        String result = runner.resume(task.getId());

        script.assertExhausted();
        assertEquals("handled catalog error", result);
        assertEquals(0, globalExtraTool.callCount());
        assertEquals(allowedCallsBefore, recordingTool.callCount());
        assertEquals(TaskStatus.COMPLETED, stateStore.getTask(task.getId()).getStatus());
        assertEquals(ToolCallStatus.DONE,
                toolCallRepository.findById(injected.id()).orElseThrow().getStatus());
        List<MessageEntity> messages = messageRepository.findByTaskIdOrderByIdAsc(task.getId());
        assertEquals(List.of("system", "user", "assistant", "tool", "assistant"),
                messages.stream().map(MessageEntity::getRole).toList());
        assertTrue(messages.get(3).getContent().contains("不存在名为 'global-extra' 的工具"));
    }

    private List<EventEntity> durableEvents(String taskId) {
        return eventRepository.findByTaskIdAndIdGreaterThanOrderByIdAsc(taskId, 0L);
    }

    private static List<Object> roles(List<Map<String, Object>> messages) {
        return messages.stream().map(message -> message.get("role")).toList();
    }

    private static Map<String, Object> assistantWithCall(ToolCall call) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of(Map.of(
                "id", call.id(),
                "type", "function",
                "function", Map.of("name", call.name(), "arguments", call.arguments()))));
        return assistant;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        SwitchableLlmClient scriptedLlmDelegate() {
            return new SwitchableLlmClient();
        }

        @Bean
        RecordingTool recordingTool() {
            return new RecordingTool(
                    "recording", IdempotencyClass.READ_ONLY, ApprovalPolicy.NONE, "recorded-result");
        }

        @Bean
        GlobalExtraTool globalExtraTool() {
            return new GlobalExtraTool();
        }
    }

    static final class SwitchableLlmClient implements LlmClient {
        private final AtomicReference<LlmClient> delegate = new AtomicReference<>();

        void use(LlmClient client) {
            delegate.set(client);
        }

        void clear() {
            delegate.set(null);
        }

        @Override
        public Decision chat(Context context, List<Map<String, Object>> toolSpecs) {
            return current().chat(context, toolSpecs);
        }

        @Override
        public Decision chatStream(
                Context context,
                List<Map<String, Object>> toolSpecs,
                Consumer<String> onToken
        ) {
            return current().chatStream(context, toolSpecs, onToken);
        }

        private LlmClient current() {
            LlmClient current = delegate.get();
            if (current == null) {
                throw new AssertionError("No scripted LLM installed for AgentRunnerIT");
            }
            return current;
        }
    }

    static final class GlobalExtraTool implements Tool {
        private final AtomicInteger calls = new AtomicInteger();

        @Override public String name() { return "global-extra"; }
        @Override public String description() { return "must stay outside the frozen catalog"; }
        @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
        @Override public String execute(JsonNode args, ToolContext context) {
            calls.incrementAndGet();
            return "unsafe global execution";
        }
        @Override public IdempotencyClass idempotency() { return IdempotencyClass.READ_ONLY; }
        int callCount() { return calls.get(); }
        void reset() { calls.set(0); }
    }
}
