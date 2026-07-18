package com.reagent.persist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.FencedExecutionException;
import com.reagent.core.TaskRunToken;
import com.reagent.core.ToolCall;
import com.reagent.core.WorkerIdentity;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
import com.reagent.testsupport.InfrastructureIT;
import com.reagent.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateStoreFencingIT extends InfrastructureIT {

    private static final long LEASE_TTL_MS = 1_000;
    private static final Instant START = Instant.parse("2026-07-18T00:00:00Z");

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ToolCallRepository toolCallRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private StreamTransport streamTransport;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MutableClock clock;
    private StateStore workerA;
    private StateStore workerB;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUpWorkers() {
        clock = new MutableClock(START);
        TaskLeaseGuard leaseGuard = new TaskLeaseGuard(taskRepository);
        workerA = stateStore("worker-a", leaseGuard);
        workerB = stateStore("worker-b", leaseGuard);
        transactions = new TransactionTemplate(transactionManager);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("staleMutators")
    void staleTokenCannotMutateTaskMessagesOrLedger(String name, StaleMutation mutation) {
        FencingFixture fixture = fencingFixture(name);
        TaskSnapshot taskBefore = taskSnapshot(fixture.taskId());
        long messageCountBefore = messageRepository.countByTaskId(fixture.taskId());
        long ledgerCountBefore = toolCallRepository.count();
        LedgerSnapshot ledgerBefore = ledgerSnapshot(fixture.call().id());

        assertThrows(FencedExecutionException.class, () -> inTransaction(() -> {
            mutation.run(workerA, fixture.oldToken(), fixture.call());
            return null;
        }));

        assertEquals(taskBefore, taskSnapshot(fixture.taskId()));
        assertEquals(messageCountBefore, messageRepository.countByTaskId(fixture.taskId()));
        assertEquals(ledgerCountBefore, toolCallRepository.count());
        assertEquals(ledgerBefore, ledgerSnapshot(fixture.call().id()));
    }

    @Test
    void currentTokenWritesDenseMessagesUnderConcurrentEventTraffic() throws Exception {
        TaskEntity task = taskRepository.save(TaskEntity.newTask("dense concurrent messages", clock.instant()));
        TaskRunToken token = inTransaction(() -> workerA.claim(task.getId())).orElseThrow();
        int messageWriters = 12;
        int eventWriters = 12;
        CyclicBarrier start = new CyclicBarrier(messageWriters + eventWriters + 1);
        List<CompletableFuture<Integer>> messageWrites = new ArrayList<>();
        List<CompletableFuture<TaskEvent>> eventWrites = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(messageWriters + eventWriters)) {
            for (int i = 0; i < messageWriters; i++) {
                int messageNumber = i;
                messageWrites.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return inTransaction(() -> workerA.appendAssistant(
                            token, Map.of("role", "assistant", "content", "message-" + messageNumber)));
                }, executor));
            }
            for (int i = 0; i < eventWriters; i++) {
                int eventNumber = i;
                eventWrites.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return streamTransport.publish(
                            token, TaskEvent.Type.STEP, Map.of("event", eventNumber));
                }, executor));
            }
            start.await();
            List<Integer> returnedSequences = messageWrites.stream()
                    .map(CompletableFuture::join)
                    .sorted()
                    .toList();
            eventWrites.forEach(CompletableFuture::join);

            assertEquals(range(messageWriters), returnedSequences);
            assertEquals(range(messageWriters), messageRepository.findByTaskIdOrderByIdAsc(task.getId()).stream()
                    .map(MessageEntity::getSeq)
                    .sorted()
                    .toList());
            List<EventEntity> durableEvents =
                    eventRepository.findByTaskIdAndIdGreaterThanOrderByIdAsc(task.getId(), 0L);
            assertEquals(eventWriters, durableEvents.size());
            assertEquals(eventWriters, durableEvents.stream()
                    .filter(event -> TaskEvent.Type.STEP.name().equals(event.getType()))
                    .count());
            assertEquals(range(eventWriters), durableEvents.stream()
                    .map(event -> eventNumber(event.getData()))
                    .sorted()
                    .toList());
        }
    }

    @Test
    void oldEpochCannotAppendDurableEvent() {
        FencingFixture fixture = fencingFixture("durable event");
        long eventCountBefore = eventRepository.count();

        assertThrows(FencedExecutionException.class, () -> inTransaction(() ->
                streamTransport.publish(
                        fixture.oldToken(), TaskEvent.Type.STEP, Map.of("step", "stale"))));

        assertEquals(eventCountBefore, eventRepository.count());
    }

    @Test
    void winningEpochCanAppendTerminalEventAfterLeaseRelease() {
        TaskEntity task = taskRepository.save(TaskEntity.newTask("terminal event", clock.instant()));
        TaskRunToken token = inTransaction(() -> workerA.claim(task.getId())).orElseThrow();
        inTransaction(() -> {
            workerA.completeTask(token, "done");
            return null;
        });
        long eventCountBefore = eventRepository.count();

        TaskEvent event = inTransaction(() -> streamTransport.publish(
                token, TaskEvent.Type.COMPLETED, Map.of("result", "done")));

        assertEquals(eventCountBefore + 1, eventRepository.count());
        assertEquals(TaskEvent.Type.COMPLETED.name(),
                eventRepository.findById(Long.parseLong(event.eventId())).orElseThrow().getType());
    }

    @Test
    void pauseTaskAtomicallyConsumesControlSignal() {
        TaskEntity task = taskRepository.save(TaskEntity.newTask("atomic pause", clock.instant()));
        TaskRunToken token = inTransaction(() -> workerA.claim(task.getId())).orElseThrow();
        inTransaction(() -> {
            workerA.requestControl(task.getId(), "PAUSE");
            return null;
        });

        inTransaction(() -> {
            workerA.pauseTask(token);
            return null;
        });

        TaskEntity paused = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.PAUSED, paused.getStatus());
        assertEquals("NONE", paused.getControlSignal());
    }

    @Test
    void cancelTaskAtomicallyConsumesControlSignal() {
        TaskEntity task = taskRepository.save(TaskEntity.newTask("atomic cancel", clock.instant()));
        TaskRunToken token = inTransaction(() -> workerA.claim(task.getId())).orElseThrow();
        inTransaction(() -> {
            workerA.requestControl(task.getId(), "CANCEL");
            return null;
        });

        inTransaction(() -> {
            workerA.cancelTask(token, "cancelled");
            return null;
        });

        TaskEntity cancelled = taskRepository.findById(task.getId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, cancelled.getStatus());
        assertEquals("NONE", cancelled.getControlSignal());
    }

    @Test
    void taskCannotReadOrMutateAnotherTasksLedgerWhenCallIdCollides() {
        assertAll(
                () -> {
                    ForeignLedgerFixture fixture = foreignLedgerFixture("status");
                    assertEquals(ToolCallStatus.PENDING,
                            workerA.statusOf(fixture.taskAId(), fixture.call().id()));
                },
                () -> assertForeignLedgerMutationRejected("appendAssistant", (store, token, call) ->
                        store.appendAssistant(token, assistantWithCall(call))),
                () -> assertForeignLedgerMutationRejected("markInProgress", (store, token, call) ->
                        store.markInProgress(token, call)),
                () -> assertForeignLedgerMutationRejected("recordToolResult", (store, token, call) ->
                        store.recordToolResult(token, call, "task-a result")),
                () -> assertForeignLedgerMutationRejected("markInDoubt", (store, token, call) ->
                        store.markInDoubt(token, call, "task-a doubt")));
    }

    @Test
    void taskCannotMutateLegacyUnownedLedgerWhenCallIdCollides() {
        assertAll(
                () -> assertLegacyLedgerMutationRejected("appendAssistant", (store, token, call) ->
                        store.appendAssistant(token, assistantWithCall(call))),
                () -> assertLegacyLedgerMutationRejected("markInProgress", (store, token, call) ->
                        store.markInProgress(token, call)),
                () -> assertLegacyLedgerMutationRejected("recordToolResult", (store, token, call) ->
                        store.recordToolResult(token, call, "current-task result")),
                () -> assertLegacyLedgerMutationRejected("markInDoubt", (store, token, call) ->
                        store.markInDoubt(token, call, "current-task doubt")));
    }

    private static Stream<Arguments> staleMutators() {
        return Stream.of(
                Arguments.of("incrementRecoveryCount", (StaleMutation) (store, token, call) ->
                        store.incrementRecoveryCount(token)),
                Arguments.of("appendAssistant", (StaleMutation) (store, token, call) ->
                        store.appendAssistant(token, assistantWithCall(call))),
                Arguments.of("markInProgress", (StaleMutation) (store, token, call) ->
                        store.markInProgress(token, call)),
                Arguments.of("recordToolResult", (StaleMutation) (store, token, call) ->
                        store.recordToolResult(token, call, "stale result")),
                Arguments.of("markInDoubt", (StaleMutation) (store, token, call) ->
                        store.markInDoubt(token, call, "stale doubt")),
                Arguments.of("clearControlSignal", (StaleMutation) (store, token, call) ->
                        store.clearControlSignal(token)),
                Arguments.of("pauseTask", (StaleMutation) (store, token, call) ->
                        store.pauseTask(token)),
                Arguments.of("cancelTask", (StaleMutation) (store, token, call) ->
                        store.cancelTask(token, "stale cancel")),
                Arguments.of("completeTask", (StaleMutation) (store, token, call) ->
                        store.completeTask(token, "stale answer")),
                Arguments.of("failTask", (StaleMutation) (store, token, call) ->
                        store.failTask(token, "stale failure"))
        );
    }

    private FencingFixture fencingFixture(String name) {
        TaskEntity task = taskRepository.save(TaskEntity.newTask("stale mutator " + name, clock.instant()));
        ToolCall call = new ToolCall("call-" + task.getId(), "read_file", "{\"path\":\"README.md\"}");
        toolCallRepository.save(new ToolCallEntity(
                call.id(), task.getId(), call.name(), call.arguments(), clock.instant()));
        TaskRunToken oldToken = inTransaction(() -> workerA.claim(task.getId())).orElseThrow();
        clock.advance(Duration.ofMillis(LEASE_TTL_MS + 1));
        inTransaction(() -> workerB.claim(task.getId())).orElseThrow();
        inTransaction(() -> workerB.requestControl(task.getId(), "CANCEL"));
        return new FencingFixture(task.getId(), oldToken, call);
    }

    private ForeignLedgerFixture foreignLedgerFixture(String name) {
        TaskEntity taskA = taskRepository.save(TaskEntity.newTask("task A " + name, clock.instant()));
        TaskEntity taskB = taskRepository.save(TaskEntity.newTask("task B " + name, clock.instant()));
        ToolCall call = new ToolCall(
                "shared-call-" + taskA.getId(), "read_file", "{\"path\":\"README.md\"}");
        ToolCallEntity taskBCall = new ToolCallEntity(
                call.id(), taskB.getId(), call.name(), call.arguments(), clock.instant());
        taskBCall.markDone("task-b result", clock.instant());
        toolCallRepository.save(taskBCall);
        TaskRunToken taskAToken = inTransaction(() -> workerA.claim(taskA.getId())).orElseThrow();
        return new ForeignLedgerFixture(taskA.getId(), taskAToken, call);
    }

    private LegacyLedgerFixture legacyLedgerFixture(String name) {
        TaskEntity task = taskRepository.save(TaskEntity.newTask("current task " + name, clock.instant()));
        ToolCall call = new ToolCall(
                "legacy-call-" + task.getId(), "read_file", "{\"path\":\"README.md\"}");
        ToolCallEntity legacyCall = new ToolCallEntity(
                call.id(), null, call.name(), call.arguments(), clock.instant());
        legacyCall.markDone("legacy result", clock.instant());
        toolCallRepository.save(legacyCall);
        TaskRunToken token = inTransaction(() -> workerA.claim(task.getId())).orElseThrow();
        return new LegacyLedgerFixture(task.getId(), token, call);
    }

    private void assertForeignLedgerMutationRejected(String name, ForeignLedgerMutation mutation) {
        ForeignLedgerFixture fixture = foreignLedgerFixture(name);
        long taskAMessageCountBefore = messageRepository.countByTaskId(fixture.taskAId());
        LedgerSnapshot taskBLedgerBefore = ledgerSnapshot(fixture.call().id());

        assertAll(name,
                () -> assertThrows(IllegalStateException.class, () -> inTransaction(() -> {
                    mutation.run(workerA, fixture.taskAToken(), fixture.call());
                    return null;
                })),
                () -> assertEquals(taskAMessageCountBefore,
                        messageRepository.countByTaskId(fixture.taskAId())),
                () -> assertEquals(taskBLedgerBefore, ledgerSnapshot(fixture.call().id())));
    }

    private void assertLegacyLedgerMutationRejected(String name, ForeignLedgerMutation mutation) {
        LegacyLedgerFixture fixture = legacyLedgerFixture(name);
        long taskMessageCountBefore = messageRepository.countByTaskId(fixture.taskId());
        LedgerSnapshot legacyLedgerBefore = ledgerSnapshot(fixture.call().id());

        assertAll(name,
                () -> assertThrows(IllegalStateException.class, () -> inTransaction(() -> {
                    mutation.run(workerA, fixture.token(), fixture.call());
                    return null;
                })),
                () -> assertEquals(taskMessageCountBefore,
                        messageRepository.countByTaskId(fixture.taskId())),
                () -> assertEquals(legacyLedgerBefore, ledgerSnapshot(fixture.call().id())));
    }

    private StateStore stateStore(String workerId, TaskLeaseGuard leaseGuard) {
        return new StateStore(taskRepository, messageRepository, toolCallRepository, objectMapper,
                new WorkerIdentity(workerId, "0"), LEASE_TTL_MS, clock, leaseGuard);
    }

    private TaskSnapshot taskSnapshot(String taskId) {
        TaskEntity task = taskRepository.findById(taskId).orElseThrow();
        return new TaskSnapshot(task.getStatus(), task.getResult(), task.getRecoveryCount(),
                task.getOwnerId(), task.getLeaseExpiresAt(), task.getLeaseEpoch(),
                task.getControlSignal(), task.getUpdatedAt());
    }

    private LedgerSnapshot ledgerSnapshot(String callId) {
        ToolCallEntity call = toolCallRepository.findById(callId).orElseThrow();
        return new LedgerSnapshot(call.getTaskId(), call.getStatus(), call.getResult(),
                call.getAttemptCount(), call.getStartedAt());
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private static Map<String, Object> assistantWithCall(ToolCall call) {
        return Map.of(
                "role", "assistant",
                "content", "stale assistant",
                "tool_calls", List.of(Map.of(
                        "id", call.id(),
                        "type", "function",
                        "function", Map.of("name", call.name(), "arguments", call.arguments()))));
    }

    private static List<Integer> range(int endExclusive) {
        return java.util.stream.IntStream.range(0, endExclusive).boxed().toList();
    }

    private int eventNumber(String data) {
        try {
            return objectMapper.readTree(data).path("event").asInt(-1);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("invalid durable event data: " + data, exception);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception exception) {
            throw new IllegalStateException("concurrent write barrier failed", exception);
        }
    }

    @FunctionalInterface
    private interface StaleMutation {
        void run(StateStore store, TaskRunToken token, ToolCall call);
    }

    @FunctionalInterface
    private interface ForeignLedgerMutation {
        void run(StateStore store, TaskRunToken token, ToolCall call);
    }

    private record FencingFixture(String taskId, TaskRunToken oldToken, ToolCall call) {
    }

    private record ForeignLedgerFixture(String taskAId, TaskRunToken taskAToken, ToolCall call) {
    }

    private record LegacyLedgerFixture(String taskId, TaskRunToken token, ToolCall call) {
    }

    private record TaskSnapshot(TaskStatus status, String result, int recoveryCount,
                                String ownerId, Instant leaseExpiresAt, long leaseEpoch,
                                String controlSignal, Instant updatedAt) {
    }

    private record LedgerSnapshot(String taskId, ToolCallStatus status, String result,
                                  int attemptCount, Instant startedAt) {
    }
}
