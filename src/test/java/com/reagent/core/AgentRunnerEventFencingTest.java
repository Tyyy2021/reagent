package com.reagent.core;

import com.reagent.llm.LlmClient;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskStatus;
import com.reagent.sandbox.WorkspaceStore;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
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

class AgentRunnerEventFencingTest {

    @Test
    void finalAnswerPublishesEveryRunEventWithClaimedToken(@TempDir Path workspace) {
        TaskEntity task = TaskEntity.newTask("finish safely", Instant.EPOCH);
        String taskId = task.getId();
        TaskRunToken token = new TaskRunToken(taskId, "worker-a", 7);
        StateStore stateStore = new FinalAnswerStateStore(task, token);
        RecordingTransport transport = new RecordingTransport();
        AgentRunner runner = new AgentRunner(
                new FinalAnswerLlm(),
                new ToolRegistry(List.of()),
                null,
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
        AgentRunner runner = new AgentRunner(
                new ThrowingLlm(),
                new ToolRegistry(List.of()),
                null,
                stateStore,
                new ShutdownState(),
                new FixedWorkspaceStore(workspace),
                new InFlightTasks(),
                transport,
                new TaskControl(),
                OpenTelemetry.noop().getTracer("agent-runner-failure-fencing-test"),
                new WorkerIdentity("worker-a", "0"),
                3);

        AgentRunner.RunResult result = runner.run("fail then fence");

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
        AgentRunner runner = new AgentRunner(
                new FinalAnswerLlm(),
                new ToolRegistry(List.of()),
                null,
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

    private static class FinalAnswerStateStore extends StateStore {
        private final TaskEntity task;
        private final TaskRunToken token;

        private FinalAnswerStateStore(TaskEntity task, TaskRunToken token) {
            super(null, null, null, null, new WorkerIdentity("worker-a", "0"),
                    1_000, Clock.systemUTC(), null);
            this.task = task;
            this.token = token;
        }

        @Override
        public TaskEntity createTask(String goal, String systemPrompt) {
            return task;
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

    private static final class FinalAnswerLlm implements LlmClient {
        @Override
        public Decision chat(Context context, List<Map<String, Object>> toolSpecs) {
            throw new UnsupportedOperationException("AgentRunner uses chatStream");
        }

        @Override
        public Decision chatStream(Context context, List<Map<String, Object>> toolSpecs,
                                   java.util.function.Consumer<String> onToken) {
            onToken.accept("done");
            return Decision.finalAnswer("done", Map.of("role", "assistant", "content", "done"));
        }
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
