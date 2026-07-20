package com.reagent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.llm.LlmClient;
import com.reagent.persist.EventEntity;
import com.reagent.persist.EventRepository;
import com.reagent.persist.MessageEntity;
import com.reagent.persist.MessageRepository;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskLeaseGuard;
import com.reagent.persist.TaskRepository;
import com.reagent.persist.TaskStatus;
import com.reagent.persist.ToolCallEntity;
import com.reagent.persist.ToolCallRepository;
import com.reagent.persist.ToolCallStatus;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.ToolCatalogResolver;
import com.reagent.sandbox.WorkspaceStore;
import com.reagent.stream.EventStore;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
import com.reagent.stream.TaskEventBus;
import com.reagent.testsupport.InfrastructureIT;
import com.reagent.testsupport.MutableClock;
import com.reagent.testsupport.RecordingTool;
import com.reagent.testsupport.ScriptedLlmClient;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.ToolExecutor;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(AgentRunnerRecoveryIT.TestBeans.class)
class AgentRunnerRecoveryIT extends InfrastructureIT {

    private static final Instant START = Instant.parse("2026-07-19T00:00:00Z");
    private static final long LEASE_TTL_MS = 1_000;
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    @DynamicPropertySource
    static void recoveryProperties(DynamicPropertyRegistry registry) {
        registry.add("reagent.profiles.definitions.coding.tool-names", () -> "recovery-recording");
    }

    @Autowired private TaskRepository taskRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ToolCallRepository toolCallRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AgentProfileRegistry profiles;
    @Autowired private ToolCatalogResolver catalogResolver;
    @Autowired private ToolExecutor toolExecutor;
    @Autowired private EventStore eventStore;
    @Autowired private WorkspaceStore workspaceStore;
    @Autowired private TransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("recoveryRecordingTool") private RecordingTool recordingTool;

    private MutableClock clock;

    @BeforeEach
    void clearDurableRuntime() {
        jdbc.update("DELETE FROM event");
        jdbc.update("DELETE FROM tool_call");
        jdbc.update("DELETE FROM message");
        jdbc.update("DELETE FROM task");
        clock = new MutableClock(START);
    }

    @Test
    void oneShotAssistantCommitCrashLeavesRunningPendingLedgerWithoutResult() {
        ToolCall call = new ToolCall("call-crashed", recordingTool.name(), "{\"value\":3}");
        ScriptedLlmClient script = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of(recordingTool.name()), messages ->
                                roles(messages).equals(List.of("system", "user")),
                        Decision.tools(assistantWithCall(call), List.of(call)))));
        AtomicBoolean oneShot = new AtomicBoolean();
        AtomicReference<FaultContext> observed = new AtomicReference<>();
        FaultInjector crashAfterAssistantCommit = (point, context) -> {
            if (point == FaultPoint.AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL
                    && oneShot.compareAndSet(false, true)) {
                observed.set(context);
                throw new InjectedWorkerCrashException(point, context);
            }
        };
        WorkerRuntime workerA = worker(
                "worker-crash", script, crashAfterAssistantCommit,
                new TaskEventBus(eventStore, clock), MAX_RECOVERY_ATTEMPTS);
        int toolCallsBefore = recordingTool.callCount();

        InjectedWorkerCrashException crash = assertThrows(
                InjectedWorkerCrashException.class,
                () -> workerA.runner().run("crash after committed assistant", "coding"));

        script.assertExhausted();
        FaultContext boundary = observed.get();
        assertNotNull(boundary);
        assertEquals(FaultPoint.AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL, crash.point());
        assertEquals(boundary, crash.faultContext());
        String taskId = boundary.taskId();
        TaskEntity crashed = taskRepository.findById(taskId).orElseThrow();
        ToolCallEntity pending = toolCallRepository.findById(call.id()).orElseThrow();
        assertEquals(TaskStatus.RUNNING, crashed.getStatus());
        assertEquals("worker-crash", crashed.getOwnerId());
        assertEquals(ToolCallStatus.PENDING, pending.getStatus());
        assertEquals(0, pending.getAttemptCount());
        assertNull(pending.getResult());
        assertEquals(boundary.batchSequence().orElseThrow(), pending.getAssistantMessageSeq());
        assertEquals(List.of("system", "user", "assistant"), roles(taskId));
        assertEquals(toolCallsBefore, recordingTool.callCount());
        assertFalse(durableTypes(taskId).contains(TaskEvent.Type.TOOL_RESULT));
        assertFalse(durableTypes(taskId).contains(TaskEvent.Type.FAILED));
    }

    @Test
    void takeoverReusesSamePersistedCallIdAndFencesReleasedWorkerWrite() throws Exception {
        ToolCall call = new ToolCall("call-recovered", recordingTool.name(), "{\"value\":7}");
        ScriptedLlmClient workerAScript = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of(recordingTool.name()), messages ->
                        roles(messages).equals(List.of("system", "user")),
                        Decision.tools(assistantWithCall(call), List.of(call)))));
        ScriptedLlmClient workerBScript = new ScriptedLlmClient(List.of(
                ScriptedLlmClient.turn(Set.of(recordingTool.name()), messages ->
                        roles(messages).equals(List.of("system", "user", "assistant", "tool"))
                                && call.id().equals(messages.getLast().get("tool_call_id")),
                        Decision.finalAnswer(
                                "recovered answer",
                                Map.of("role", "assistant", "content", "recovered answer")))));

        CountDownLatch assistantCommitted = new CountDownLatch(1);
        CountDownLatch releaseWorkerA = new CountDownLatch(1);
        AtomicReference<FaultContext> crashBoundary = new AtomicReference<>();
        AtomicBoolean oneShot = new AtomicBoolean();
        FaultInjector blockingCrash = (point, context) -> {
            if (point == FaultPoint.AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL
                    && oneShot.compareAndSet(false, true)) {
                crashBoundary.set(context);
                assistantCommitted.countDown();
                await(releaseWorkerA, "release stale Worker A");
            }
        };

        CapturingFencedTransport workerATransport = new CapturingFencedTransport(
                new TaskEventBus(eventStore, clock));
        WorkerRuntime workerA = worker(
                "worker-a", workerAScript, blockingCrash, workerATransport, MAX_RECOVERY_ATTEMPTS);
        WorkerRuntime workerB = worker(
                "worker-b", workerBScript, FaultInjector.none(),
                new TaskEventBus(eventStore, clock), MAX_RECOVERY_ATTEMPTS);
        int toolCallsBefore = recordingTool.callCount();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AgentRunner.RunResult> staleRun = executor.submit(
                    () -> workerA.runner().run("recover committed assistant", "coding"));
            await(assistantCommitted, "assistant commit crash boundary");

            FaultContext boundary = crashBoundary.get();
            assertNotNull(boundary);
            String taskId = boundary.taskId();
            TaskEntity crashed = taskRepository.findById(taskId).orElseThrow();
            ToolCallEntity pending = toolCallRepository.findById(call.id()).orElseThrow();
            assertEquals(TaskStatus.RUNNING, crashed.getStatus());
            assertEquals("worker-a", crashed.getOwnerId());
            assertEquals(ToolCallStatus.PENDING, pending.getStatus());
            assertEquals(0, pending.getAttemptCount());
            assertNull(pending.getResult());
            assertEquals(2, pending.getAssistantMessageSeq());
            assertEquals(List.of("system", "user", "assistant"), roles(taskId));
            assertEquals(toolCallsBefore, recordingTool.callCount());
            assertFalse(durableTypes(taskId).contains(TaskEvent.Type.TOOL_RESULT));
            assertFalse(durableTypes(taskId).contains(TaskEvent.Type.FAILED));

            clock.advance(Duration.ofMillis(LEASE_TTL_MS + 1));
            String recovered;
            try {
                recovered = workerB.runner().recover(taskId);
            } finally {
                releaseWorkerA.countDown();
            }

            assertEquals("recovered answer", recovered);
            AgentRunner.RunResult staleResult = staleRun.get(
                    AWAIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            assertEquals(taskId, staleResult.taskId());
            assertEquals("本任务已被其它 worker 接管(fence),本 worker 停止驱动。",
                    staleResult.result());
            workerAScript.assertExhausted();
            workerBScript.assertExhausted();

            TaskEntity completed = taskRepository.findById(taskId).orElseThrow();
            ToolCallEntity done = toolCallRepository.findById(call.id()).orElseThrow();
            assertEquals(TaskStatus.COMPLETED, completed.getStatus());
            assertEquals("recovered answer", completed.getResult());
            assertEquals(ToolCallStatus.DONE, done.getStatus());
            assertEquals("recorded-result", done.getResult());
            assertEquals(1, done.getAttemptCount());
            assertEquals(toolCallsBefore + 1, recordingTool.callCount());
            assertEquals(call.id(), recordingTool.lastContext().orElseThrow().idempotencyKey());
            assertEquals("worker-b", recordingTool.lastContext().orElseThrow()
                    .runToken().orElseThrow().workerId());
            assertEquals(List.of("system", "user", "assistant", "tool", "assistant"), roles(taskId));

            FencedExecutionException fenced = workerATransport.fencedException();
            assertNotNull(fenced, "Worker A's first post-release durable write must hit the fence");
            assertEquals(taskId, fenced.token().taskId());
            assertEquals("worker-a", fenced.token().workerId());
            assertEquals(completed.getLeaseEpoch(), fenced.actualEpoch());
            assertEquals(TaskStatus.COMPLETED, fenced.actualStatus());
            assertFalse(durableTypes(taskId).contains(TaskEvent.Type.FAILED));
            assertEquals(TaskStatus.COMPLETED,
                    taskRepository.findById(taskId).orElseThrow().getStatus());
        } finally {
            releaseWorkerA.countDown();
        }
    }

    @Test
    void stopsAtMaxRecoveryAttemptsWithoutAnotherLlmOrToolCall() {
        List<ToolCall> calls = List.of(
                new ToolCall("call-attempt-1", recordingTool.name(), "{}"),
                new ToolCall("call-attempt-2", recordingTool.name(), "{}"),
                new ToolCall("call-attempt-3", recordingTool.name(), "{}"));
        ScriptedLlmClient script = new ScriptedLlmClient(List.of(
                scriptedToolTurn(calls.get(0), 2),
                scriptedToolTurn(calls.get(1), 4),
                scriptedToolTurn(calls.get(2), 6)));
        FaultInjector crashAfterDurableResult = (point, context) -> {
            if (point == FaultPoint.AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER) {
                throw new InjectedWorkerCrashException(point, context);
            }
        };
        WorkerRuntime worker = worker(
                "worker-max", script, crashAfterDurableResult,
                new TaskEventBus(eventStore, clock), MAX_RECOVERY_ATTEMPTS);
        TaskProfileSnapshot snapshot = profiles.snapshot("coding");
        TaskEntity task = worker.stateStore().createTask("bounded offline recovery", snapshot);
        int toolCallsBefore = recordingTool.callCount();

        for (int expectedAttempt = 1; expectedAttempt <= MAX_RECOVERY_ATTEMPTS; expectedAttempt++) {
            assertThrows(InjectedWorkerCrashException.class,
                    () -> worker.runner().recover(task.getId()));
            TaskEntity stillRunning = taskRepository.findById(task.getId()).orElseThrow();
            assertEquals(TaskStatus.RUNNING, stillRunning.getStatus());
            assertEquals(expectedAttempt, stillRunning.getRecoveryCount());
        }
        script.assertExhausted();

        String stopped = worker.runner().recover(task.getId());

        TaskEntity failed = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals("超过最大自动恢复次数,已止损标记 FAILED。", stopped);
        assertEquals(TaskStatus.FAILED, failed.getStatus());
        assertTrue(failed.getResult().contains("最大自动恢复次数(" + MAX_RECOVERY_ATTEMPTS + ")"));
        assertEquals(MAX_RECOVERY_ATTEMPTS, failed.getRecoveryCount(),
                "the persisted attempt count must stop at the configured cap");
        assertEquals(MAX_RECOVERY_ATTEMPTS, script.callCount(),
                "the cap check must not make an extra paid/network LLM turn");
        assertEquals(toolCallsBefore + MAX_RECOVERY_ATTEMPTS, recordingTool.callCount(),
                "only the exact offline recording-tool attempts may execute");
        assertEquals(MAX_RECOVERY_ATTEMPTS,
                calls.stream().filter(call -> toolCallRepository.findById(call.id())
                        .filter(entity -> entity.getStatus() == ToolCallStatus.DONE)
                        .isPresent()).count());
    }

    private WorkerRuntime worker(
            String workerId,
            LlmClient llm,
            FaultInjector faultInjector,
            StreamTransport transport,
            int maxAttempts
    ) {
        WorkerIdentity identity = new WorkerIdentity(workerId, "0");
        StateStore stateStore = transactionalStateStore(identity);
        TaskControl taskControl = new TaskControl();
        ToolBatchCoordinator coordinator = new DefaultToolBatchCoordinator(
                stateStore, toolExecutor, transport, taskControl, faultInjector);
        AgentRunner runner = new AgentRunner(
                llm,
                profiles,
                catalogResolver,
                coordinator,
                faultInjector,
                stateStore,
                new ShutdownState(),
                workspaceStore,
                new InFlightTasks(),
                transport,
                taskControl,
                OpenTelemetry.noop().getTracer("agent-runner-recovery-it-" + workerId),
                identity,
                maxAttempts);
        return new WorkerRuntime(stateStore, runner);
    }

    private StateStore transactionalStateStore(WorkerIdentity identity) {
        StateStore target = new StateStore(
                taskRepository,
                messageRepository,
                toolCallRepository,
                objectMapper,
                profiles,
                identity,
                LEASE_TTL_MS,
                clock,
                new TaskLeaseGuard(taskRepository));
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (StateStore) factory.getProxy();
    }

    private List<String> roles(String taskId) {
        return messageRepository.findByTaskIdOrderByIdAsc(taskId).stream()
                .map(MessageEntity::getRole)
                .toList();
    }

    private static List<Object> roles(List<Map<String, Object>> messages) {
        return messages.stream().map(message -> message.get("role")).toList();
    }

    private List<TaskEvent.Type> durableTypes(String taskId) {
        List<EventEntity> events = eventRepository.findByTaskIdAndIdGreaterThanOrderByIdAsc(taskId, 0L);
        return events.stream().map(event -> TaskEvent.Type.valueOf(event.getType())).toList();
    }

    private static ScriptedLlmClient.Turn scriptedToolTurn(ToolCall call, int expectedMessageCount) {
        return ScriptedLlmClient.turn(Set.of(call.name()), messages ->
                        messages.size() == expectedMessageCount
                                && roles(messages).equals(expectedRoles(expectedMessageCount)),
                Decision.tools(assistantWithCall(call), List.of(call)));
    }

    private static List<Object> expectedRoles(int messageCount) {
        java.util.ArrayList<Object> roles = new java.util.ArrayList<>();
        roles.add("system");
        roles.add("user");
        for (int index = 2; index < messageCount; index += 2) {
            roles.add("assistant");
            roles.add("tool");
        }
        return List.copyOf(roles);
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

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(AWAIT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new AssertionError("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for " + description, exception);
        }
    }

    private record WorkerRuntime(StateStore stateStore, AgentRunner runner) {
    }

    private static final class CapturingFencedTransport implements StreamTransport {
        private final StreamTransport delegate;
        private final AtomicReference<FencedExecutionException> fenced = new AtomicReference<>();

        private CapturingFencedTransport(StreamTransport delegate) {
            this.delegate = delegate;
        }

        @Override
        public TaskEvent publish(String taskId, TaskEvent.Type type, Object data) {
            return delegate.publish(taskId, type, data);
        }

        @Override
        public TaskEvent publish(TaskRunToken token, TaskEvent.Type type, Object data) {
            try {
                return delegate.publish(token, type, data);
            } catch (FencedExecutionException exception) {
                fenced.compareAndSet(null, exception);
                throw exception;
            }
        }

        @Override
        public Subscription subscribeWithReplay(String taskId, String cursor, EventSink sink) {
            return delegate.subscribeWithReplay(taskId, cursor, sink);
        }

        FencedExecutionException fencedException() {
            return fenced.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        RecordingTool recoveryRecordingTool() {
            return new RecordingTool(
                    "recovery-recording",
                    IdempotencyClass.READ_ONLY,
                    ApprovalPolicy.NONE,
                    "recorded-result");
        }
    }
}
