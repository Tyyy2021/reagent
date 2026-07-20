package com.reagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.llm.LlmClient;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskStatus;
import com.reagent.profile.SchemaHasher;
import com.reagent.profile.AgentProfileProperties;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.ToolCatalogResolver;
import com.reagent.profile.UnknownProfileException;
import com.reagent.sandbox.WorkspaceStore;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunnerEventFencingTest {

    @Test
    void finalAnswerPublishesEveryRunEventWithClaimedToken(@TempDir Path workspace) {
        TaskEntity task = TaskEntity.newTask("finish safely", Instant.EPOCH);
        String taskId = task.getId();
        TaskRunToken token = new TaskRunToken(taskId, "worker-a", 7);
        StateStore stateStore = new FinalAnswerStateStore(task, token);
        RecordingTransport transport = new RecordingTransport();
        FinalAnswerLlm llm = new FinalAnswerLlm();
        ToolCatalogResolver resolver = codingResolver();
        AgentRunner runner = new AgentRunner(
                llm,
                profileRegistry(resolver),
                resolver,
                null,
                FaultInjector.none(),
                stateStore,
                new ShutdownState(),
                new FixedWorkspaceStore(workspace),
                new InFlightTasks(),
                transport,
                new TaskControl(),
                OpenTelemetry.noop().getTracer("agent-runner-event-fencing-test"),
                new WorkerIdentity("worker-a", "0"),
                3);

        AgentRunner.RunResult result = runner.run("finish safely");

        assertEquals("done", result.result());
        assertEquals(List.of("read_file", "list_dir", "write_file", "run_command", "sleep_ms"),
                llm.seenToolNames);
        assertEquals(List.of(), transport.controlPlaneTypes);
        assertEquals(List.of(
                        TaskEvent.Type.TASK_STARTED,
                        TaskEvent.Type.STEP,
                        TaskEvent.Type.TOKEN,
                        TaskEvent.Type.COMPLETED),
                transport.fencedTypes);
        assertEquals(List.of(token, token, token, token), transport.tokens);
    }

    @Test
    void takeoverWhileRecordingFailureStopsWithoutPublishingFailed(@TempDir Path workspace) {
        TaskEntity task = TaskEntity.newTask("fail then fence", Instant.EPOCH);
        String taskId = task.getId();
        TaskRunToken token = new TaskRunToken(taskId, "worker-a", 7);
        StateStore stateStore = new FenceOnFailureStateStore(task, token);
        RecordingTransport transport = new RecordingTransport();
        ToolCatalogResolver resolver = codingResolver();
        AgentRunner runner = new AgentRunner(
                new ThrowingLlm(),
                profileRegistry(resolver),
                resolver,
                null,
                FaultInjector.none(),
                stateStore,
                new ShutdownState(),
                new FixedWorkspaceStore(workspace),
                new InFlightTasks(),
                transport,
                new TaskControl(),
                OpenTelemetry.noop().getTracer("agent-runner-failure-fencing-test"),
                new WorkerIdentity("worker-a", "0"),
                3);

        AgentRunner.RunResult result = runner.run("fail then fence", "coding");

        assertEquals("本任务已被其它 worker 接管(fence),本 worker 停止驱动。", result.result());
        assertEquals(List.of(), transport.controlPlaneTypes);
        assertEquals(List.of(TaskEvent.Type.TASK_STARTED, TaskEvent.Type.STEP), transport.fencedTypes);
    }

    @Test
    void takeoverDuringAutoRecoveryPreludeStopsWithoutPublishingFailed(@TempDir Path workspace) {
        TaskEntity task = TaskEntity.newTask("recover then fence", Instant.EPOCH);
        String taskId = task.getId();
        TaskRunToken token = new TaskRunToken(taskId, "worker-a", 7);
        StateStore stateStore = new FenceOnRecoveryCountStateStore(task, token);
        RecordingTransport transport = new RecordingTransport();
        ToolCatalogResolver resolver = codingResolver();
        AgentRunner runner = new AgentRunner(
                new FinalAnswerLlm(),
                profileRegistry(resolver),
                resolver,
                null,
                FaultInjector.none(),
                stateStore,
                new ShutdownState(),
                new FixedWorkspaceStore(workspace),
                new InFlightTasks(),
                transport,
                new TaskControl(),
                OpenTelemetry.noop().getTracer("agent-runner-recovery-fencing-test"),
                new WorkerIdentity("worker-a", "0"),
                3);

        String result = runner.recover(taskId);

        assertEquals("本任务已被其它 worker 接管(fence),本 worker 停止驱动。", result);
        assertEquals(List.of(), transport.controlPlaneTypes);
        assertEquals(List.of(), transport.fencedTypes);
    }

    @Test
    void losingRecoveryClaimDoesNotLoadProfileOrCallLlm(@TempDir Path workspace) {
        TaskEntity task = TaskEntity.newTask("lose recovery claim", Instant.EPOCH);
        LosingClaimStateStore stateStore = new LosingClaimStateStore(
                task, new TaskRunToken(task.getId(), "worker-a", 7));
        FinalAnswerLlm llm = new FinalAnswerLlm();
        ToolCatalogResolver resolver = codingResolver();
        AgentRunner runner = runner(llm, stateStore, resolver, new RecordingTransport(), workspace);

        String result = runner.recover(task.getId());

        assertEquals("任务已被其它 worker 持有,本次驱动跳过。", result);
        assertEquals(0, stateStore.profileLoads);
        assertEquals(List.of(), llm.seenToolNames);
    }

    @Test
    void winningRecoveryLoadsProfileAfterClaimAndBeforeLlm(@TempDir Path workspace) {
        TaskEntity task = TaskEntity.newTask("ordered recovery prelude", Instant.EPOCH);
        List<String> events = new ArrayList<>();
        OrderedRecoveryStateStore stateStore = new OrderedRecoveryStateStore(
                task, new TaskRunToken(task.getId(), "worker-a", 7), events);
        ToolCatalogResolver resolver = codingResolver();
        AgentRunner runner = runner(
                new OrderedFinalAnswerLlm(events), stateStore, resolver,
                new RecordingTransport(), workspace);

        assertEquals("done", runner.recover(task.getId()));

        assertTrue(events.indexOf("claim") < events.indexOf("profile"), events.toString());
        assertTrue(events.indexOf("profile") < events.indexOf("llm"), events.toString());
    }

    @Test
    void fencingDuringRecoveryProfilePreludeStopsBeforeEventsOrLlm(@TempDir Path workspace) {
        TaskEntity task = TaskEntity.newTask("fenced recovery profile", Instant.EPOCH);
        TaskRunToken token = new TaskRunToken(task.getId(), "worker-a", 7);
        StateStore stateStore = new FenceOnProfileLoadStateStore(task, token);
        FinalAnswerLlm llm = new FinalAnswerLlm();
        RecordingTransport transport = new RecordingTransport();
        ToolCatalogResolver resolver = codingResolver();
        AgentRunner runner = runner(llm, stateStore, resolver, transport, workspace);

        String result = runner.recover(task.getId());

        assertEquals("本任务已被其它 worker 接管(fence),本 worker 停止驱动。", result);
        assertEquals(List.of(), llm.seenToolNames);
        assertEquals(List.of(), transport.controlPlaneTypes);
        assertEquals(List.of(), transport.fencedTypes);
    }

    @Test
    void unknownProfileFailsBeforeTaskCreation(@TempDir Path workspace) {
        TaskEntity task = TaskEntity.newTask("must not create", Instant.EPOCH);
        TaskRunToken token = new TaskRunToken(task.getId(), "worker-a", 7);
        CountingStateStore stateStore = new CountingStateStore(task, token);
        ToolCatalogResolver resolver = codingResolver();
        AgentRunner runner = new AgentRunner(
                new FinalAnswerLlm(),
                profileRegistry(resolver),
                resolver,
                null,
                FaultInjector.none(),
                stateStore,
                new ShutdownState(),
                new FixedWorkspaceStore(workspace),
                new InFlightTasks(),
                new RecordingTransport(),
                new TaskControl(),
                OpenTelemetry.noop().getTracer("agent-runner-profile-test"),
                new WorkerIdentity("worker-a", "0"),
                3);

        assertThrows(UnknownProfileException.class,
                () -> runner.run("must not create", "incident-ops"));
        assertEquals(0, stateStore.creations);
    }

    private static class FinalAnswerStateStore extends StateStore {
        private final TaskEntity task;
        private final TaskRunToken token;

        private FinalAnswerStateStore(TaskEntity task, TaskRunToken token) {
            super(null, null, null, null, null, new WorkerIdentity("worker-a", "0"),
                    1_000, Clock.systemUTC(), null);
            this.task = task;
            this.token = token;
        }

        @Override
        public TaskEntity createTask(String goal, TaskProfileSnapshot snapshot) {
            return task;
        }

        @Override
        public TaskProfileSnapshot loadProfile(String taskId) {
            return codingResolver().snapshot(com.reagent.profile.AgentProfileDefinition.coding());
        }

        @Override
        public TaskProfileSnapshot loadProfile(TaskRunToken token) {
            return codingResolver().snapshot(com.reagent.profile.AgentProfileDefinition.coding());
        }

        @Override
        public Optional<TaskRunToken> claim(String taskId) {
            return Optional.of(token);
        }

        @Override
        public Context loadContext(String taskId) {
            return new Context("system");
        }

        @Override
        public String readControlSignal(String taskId) {
            return "NONE";
        }

        @Override
        public int appendAssistant(TaskRunToken token, Map<String, Object> assistantMessage) {
            return 0;
        }

        @Override
        public void completeTask(TaskRunToken token, String answer) {
            task.complete(answer, Instant.EPOCH.plusSeconds(1));
        }

        @Override
        public void failTask(TaskRunToken token, String error) {
            task.fail(error, Instant.EPOCH.plusSeconds(1));
        }

        @Override
        public TaskEntity getTask(String taskId) {
            return task;
        }
    }

    private static final class FenceOnFailureStateStore extends FinalAnswerStateStore {
        private final TaskRunToken token;

        private FenceOnFailureStateStore(TaskEntity task, TaskRunToken token) {
            super(task, token);
            this.token = token;
        }

        @Override
        public void failTask(TaskRunToken token, String error) {
            throw new FencedExecutionException(
                    this.token, "worker-b", this.token.leaseEpoch() + 1, TaskStatus.RUNNING);
        }
    }

    private static final class FenceOnRecoveryCountStateStore extends FinalAnswerStateStore {
        private final TaskRunToken token;

        private FenceOnRecoveryCountStateStore(TaskEntity task, TaskRunToken token) {
            super(task, token);
            this.token = token;
        }

        @Override
        public int incrementRecoveryCount(TaskRunToken token) {
            throw new FencedExecutionException(
                    this.token, "worker-b", this.token.leaseEpoch() + 1, TaskStatus.RUNNING);
        }
    }

    private static final class LosingClaimStateStore extends FinalAnswerStateStore {
        private int profileLoads;

        private LosingClaimStateStore(TaskEntity task, TaskRunToken token) {
            super(task, token);
        }

        @Override
        public TaskProfileSnapshot loadProfile(String taskId) {
            profileLoads++;
            return super.loadProfile(taskId);
        }

        @Override
        public TaskProfileSnapshot loadProfile(TaskRunToken token) {
            profileLoads++;
            return super.loadProfile(token);
        }

        @Override
        public Optional<TaskRunToken> claim(String taskId) {
            return Optional.empty();
        }
    }

    private static final class OrderedRecoveryStateStore extends FinalAnswerStateStore {
        private final List<String> events;

        private OrderedRecoveryStateStore(TaskEntity task, TaskRunToken token, List<String> events) {
            super(task, token);
            this.events = events;
        }

        @Override
        public TaskProfileSnapshot loadProfile(String taskId) {
            events.add("profile");
            return super.loadProfile(taskId);
        }

        @Override
        public TaskProfileSnapshot loadProfile(TaskRunToken token) {
            events.add("profile");
            return super.loadProfile(token);
        }

        @Override
        public Optional<TaskRunToken> claim(String taskId) {
            events.add("claim");
            return super.claim(taskId);
        }

        @Override
        public int incrementRecoveryCount(TaskRunToken token) {
            return 1;
        }
    }

    private static final class FenceOnProfileLoadStateStore extends FinalAnswerStateStore {
        private final TaskRunToken token;

        private FenceOnProfileLoadStateStore(TaskEntity task, TaskRunToken token) {
            super(task, token);
            this.token = token;
        }

        @Override
        public TaskProfileSnapshot loadProfile(TaskRunToken token) {
            throw new FencedExecutionException(
                    this.token, "worker-b", this.token.leaseEpoch() + 1, TaskStatus.RUNNING);
        }

        @Override
        public int incrementRecoveryCount(TaskRunToken token) {
            return 1;
        }
    }

    private static final class CountingStateStore extends FinalAnswerStateStore {
        private int creations;

        private CountingStateStore(TaskEntity task, TaskRunToken token) {
            super(task, token);
        }

        @Override
        public TaskEntity createTask(String goal, TaskProfileSnapshot snapshot) {
            creations++;
            return super.createTask(goal, snapshot);
        }
    }

    private static final class FinalAnswerLlm implements LlmClient {
        private List<String> seenToolNames = List.of();

        @Override
        public Decision chat(Context context, List<Map<String, Object>> toolSpecs) {
            throw new UnsupportedOperationException("AgentRunner uses chatStream");
        }

        @Override
        public Decision chatStream(Context context, List<Map<String, Object>> toolSpecs,
                                   java.util.function.Consumer<String> onToken) {
            seenToolNames = toolSpecs.stream().map(AgentRunnerEventFencingTest::functionName).toList();
            onToken.accept("done");
            return Decision.finalAnswer("done", Map.of("role", "assistant", "content", "done"));
        }
    }

    private record OrderedFinalAnswerLlm(List<String> events) implements LlmClient {
        @Override
        public Decision chat(Context context, List<Map<String, Object>> toolSpecs) {
            throw new UnsupportedOperationException("AgentRunner uses chatStream");
        }

        @Override
        public Decision chatStream(Context context, List<Map<String, Object>> toolSpecs,
                                   java.util.function.Consumer<String> onToken) {
            events.add("llm");
            return Decision.finalAnswer("done", Map.of("role", "assistant", "content", "done"));
        }
    }

    private static AgentRunner runner(LlmClient llm, StateStore stateStore,
                                      ToolCatalogResolver resolver, RecordingTransport transport,
                                      Path workspace) {
        return new AgentRunner(
                llm,
                profileRegistry(resolver),
                resolver,
                null,
                FaultInjector.none(),
                stateStore,
                new ShutdownState(),
                new FixedWorkspaceStore(workspace),
                new InFlightTasks(),
                transport,
                new TaskControl(),
                OpenTelemetry.noop().getTracer("agent-runner-profile-fencing-test"),
                new WorkerIdentity("worker-a", "0"),
                3);
    }

    private static ToolCatalogResolver codingResolver() {
        ObjectMapper mapper = new ObjectMapper();
        List<Tool> tools = new ArrayList<>();
        for (String name : List.of(
                "read_file", "list_dir", "write_file", "run_command", "sleep_ms", "extra_global")) {
            tools.add(new Tool() {
                @Override public String name() { return name; }
                @Override public String description() { return "test " + name; }
                @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
                @Override public String execute(JsonNode args, ToolContext ctx) { return "ok"; }
            });
        }
        return new ToolCatalogResolver(
                new ToolRegistry(tools), new SchemaHasher(mapper), new ToolProperties());
    }

    private static AgentProfileRegistry profileRegistry(ToolCatalogResolver resolver) {
        AgentProfileProperties.Profile coding = new AgentProfileProperties.Profile();
        coding.setVersion("v1");
        coding.setSystemPrompt(com.reagent.profile.AgentProfileDefinition.CODING_SYSTEM_PROMPT);
        coding.setToolNames(List.of("read_file", "list_dir", "write_file", "run_command", "sleep_ms"));
        AgentProfileProperties properties = new AgentProfileProperties();
        properties.setDefaultId("coding");
        properties.setDefinitions(Map.of("coding", coding));
        return new AgentProfileRegistry(properties, resolver);
    }

    @SuppressWarnings("unchecked")
    private static String functionName(Map<String, Object> spec) {
        return String.valueOf(((Map<String, Object>) spec.get("function")).get("name"));
    }

    private static final class ThrowingLlm implements LlmClient {
        @Override
        public Decision chat(Context context, List<Map<String, Object>> toolSpecs) {
            throw new UnsupportedOperationException("AgentRunner uses chatStream");
        }

        @Override
        public Decision chatStream(Context context, List<Map<String, Object>> toolSpecs,
                                   java.util.function.Consumer<String> onToken) {
            throw new IllegalStateException("synthetic LLM failure");
        }
    }

    private record FixedWorkspaceStore(Path workspace) implements WorkspaceStore {
        @Override
        public Path checkout(String taskId) {
            return workspace;
        }

        @Override
        public void commit(String taskId) {
        }
    }

    private static final class RecordingTransport implements StreamTransport {
        private final List<TaskEvent.Type> controlPlaneTypes = new ArrayList<>();
        private final List<TaskEvent.Type> fencedTypes = new ArrayList<>();
        private final List<TaskRunToken> tokens = new ArrayList<>();

        @Override
        public TaskEvent publish(String taskId, TaskEvent.Type type, Object data) {
            controlPlaneTypes.add(type);
            return TaskEvent.of(taskId, null, type, data, Instant.EPOCH);
        }

        @Override
        public TaskEvent publish(TaskRunToken token, TaskEvent.Type type, Object data) {
            tokens.add(token);
            fencedTypes.add(type);
            return TaskEvent.of(token.taskId(), null, type, data, Instant.EPOCH);
        }

        @Override
        public Subscription subscribeWithReplay(String taskId, String cursor, EventSink sink) {
            throw new UnsupportedOperationException("not used by AgentRunner");
        }
    }
}
