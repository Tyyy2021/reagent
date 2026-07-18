package com.reagent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.persist.MessageRepository;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskLeaseGuard;
import com.reagent.persist.TaskRepository;
import com.reagent.persist.TaskStatus;
import com.reagent.persist.ToolCallRepository;
import com.reagent.testsupport.InfrastructureIT;
import com.reagent.testsupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseFailoverIT extends InfrastructureIT {

    private static final long LEASE_TTL_MS = 1_000;
    private static final Instant START = Instant.parse("2026-07-18T00:00:00Z");

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ToolCallRepository toolCallRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private MutableClock clock;
    private StateStore workerA;
    private StateStore workerB;
    private TaskLeaseGuard leaseGuard;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUpWorkers() {
        clock = new MutableClock(START);
        workerA = stateStore("worker-a");
        workerB = stateStore("worker-b");
        leaseGuard = new TaskLeaseGuard(taskRepository);
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void onlyOneWorkerWinsConcurrentClaim() throws Exception {
        TaskEntity task = taskRepository.save(TaskEntity.newTask("concurrent claim", clock.instant()));
        CyclicBarrier start = new CyclicBarrier(3);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Optional<TaskRunToken>> claimA = CompletableFuture.supplyAsync(
                    () -> claimAfterBarrier(workerA, task.getId(), start), executor);
            CompletableFuture<Optional<TaskRunToken>> claimB = CompletableFuture.supplyAsync(
                    () -> claimAfterBarrier(workerB, task.getId(), start), executor);
            start.await();

            List<Optional<TaskRunToken>> claims = List.of(claimA.get(), claimB.get());

            assertEquals(1, claims.stream().filter(Optional::isPresent).count());
            assertEquals(1L, taskRepository.findById(task.getId()).orElseThrow().getLeaseEpoch());
        }
    }

    @Test
    void findRecoverableIncludesOwnerNullAndExpiredButNotLiveLease() {
        TaskEntity ownerNull = taskRepository.save(TaskEntity.newTask("owner null", clock.instant()));
        TaskEntity expired = TaskEntity.newTask("expired lease", clock.instant());
        expired.assignLease("dead-worker", clock.instant().minusSeconds(1), clock.instant());
        taskRepository.save(expired);
        TaskEntity live = TaskEntity.newTask("live lease", clock.instant());
        live.assignLease("live-worker", clock.instant().plusSeconds(1), clock.instant());
        taskRepository.save(live);

        List<String> recoverableIds = workerA.findRecoverable(10).stream().map(TaskEntity::getId).toList();

        assertTrue(recoverableIds.contains(ownerNull.getId()));
        assertTrue(recoverableIds.contains(expired.getId()));
        assertFalse(recoverableIds.contains(live.getId()));
    }

    @Test
    void newClaimIncrementsEpochAndFencesOldToken() {
        TaskEntity task = taskRepository.save(TaskEntity.newTask("lease takeover", clock.instant()));
        TaskRunToken oldToken = inTransaction(() -> workerA.claim(task.getId())).orElseThrow();

        clock.advance(Duration.ofMillis(LEASE_TTL_MS + 1));
        TaskRunToken newToken = inTransaction(() -> workerB.claim(task.getId())).orElseThrow();

        assertEquals(oldToken.leaseEpoch() + 1, newToken.leaseEpoch());
        assertEquals("worker-b", newToken.workerId());
        assertFalse(inTransaction(() -> workerA.renew(oldToken)));
        assertThrows(FencedExecutionException.class, () -> inTransaction(
                () -> leaseGuard.lockOwned(oldToken, Set.of(TaskStatus.RUNNING))));
        assertEquals(task.getId(), inTransaction(
                () -> leaseGuard.lockOwned(newToken, Set.of(TaskStatus.RUNNING))).getId());
    }

    private StateStore stateStore(String workerId) {
        return new StateStore(taskRepository, messageRepository, toolCallRepository, objectMapper,
                null, new WorkerIdentity(workerId, "0"), LEASE_TTL_MS, clock, leaseGuard);
    }

    private Optional<TaskRunToken> claimAfterBarrier(StateStore store, String taskId, CyclicBarrier barrier) {
        await(barrier);
        return inTransaction(() -> store.claim(taskId));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception exception) {
            throw new IllegalStateException("claim barrier failed", exception);
        }
    }
}
