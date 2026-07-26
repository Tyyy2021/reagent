package com.reagent.stream;

import com.reagent.core.TaskRunToken;
import com.reagent.persist.StateStore;
import com.reagent.testsupport.InfrastructureIT;
import com.reagent.testsupport.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = {
        "reagent.streaming.transport=redis",
        "reagent.streaming.redis-stream-ttl-sec=30"
})
@Import(RedisStreamTransportIT.FixedClockConfiguration.class)
class RedisStreamTransportIT extends InfrastructureIT {

    private static final String KEY_PREFIX = "reagent:stream:";
    private static final Instant TEST_NOW = Instant.parse("2026-07-18T09:00:00Z");

    @Autowired
    private RedisStreamTransport transport;

    @Autowired
    private StreamTransport selectedTransport;

    @Autowired
    private EventStore eventStore;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private StateStore stateStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void runtimeTokenUsesFencedAppendAndInjectedUtcClock() {
        String taskId = stateStore.createTask("redis runtime event", "system").getId();
        TaskRunToken token = stateStore.claim(taskId).orElseThrow();

        TaskEvent event = transport.publish(token, TaskEvent.Type.STEP, Map.of("step", 1));

        assertEquals(TEST_NOW, event.at());
        assertEquals(List.of(TaskEvent.Type.STEP), types(eventStore.replayAfter(taskId, 0L)));
    }

    @Test
    void replaysPersistedStepThenDeliversLiveToolResultWithoutLossOrDuplicate() throws InterruptedException {
        assertSame(transport, selectedTransport, "Redis transport must be the active StreamTransport");
        String taskId = taskId();
        transport.publish(taskId, TaskEvent.Type.STEP, Map.of("step", 1));

        List<TaskEvent> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch replayReceived = new CountDownLatch(1);
        CountDownLatch bothReceived = new CountDownLatch(2);
        StreamTransport.Subscription subscription = transport.subscribeWithReplay(taskId, "0", event -> {
            received.add(event);
            if (event.type() == TaskEvent.Type.STEP) {
                replayReceived.countDown();
            }
            bothReceived.countDown();
            return true;
        });

        assertAwait(replayReceived, "Persisted STEP was not replayed");
        transport.publish(taskId, TaskEvent.Type.TOOL_RESULT, Map.of("result", "ok"));
        assertAwait(bothReceived, "Live TOOL_RESULT was not delivered");
        subscription.close();

        assertEquals(List.of(TaskEvent.Type.STEP, TaskEvent.Type.TOOL_RESULT), types(received));
        assertEquals(2, received.stream().map(TaskEvent::eventId).distinct().count());
    }

    @Test
    void subscriptionBeforeFirstPublishWaitsForTheNewTaskStream() throws InterruptedException {
        String taskId =
                stateStore.createTask("subscribe before first publish", "system").getId();
        List<TaskEvent> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstEventReceived = new CountDownLatch(1);

        StreamTransport.Subscription subscription =
                transport.subscribeWithReplay(taskId, "0", event -> {
                    received.add(event);
                    firstEventReceived.countDown();
                    return true;
                });
        transport.publish(taskId, TaskEvent.Type.TASK_STARTED, Map.of("leaseEpoch", 1));

        assertAwait(
                firstEventReceived,
                "Subscription opened before the first XADD must remain live");
        subscription.close();
        assertEquals(List.of(TaskEvent.Type.TASK_STARTED), types(received));
    }

    @Test
    void runningTaskDoesNotTreatAPriorRunBoundaryAsAnExpiredTask()
            throws InterruptedException {
        String taskId =
                stateStore.createTask("resume after approval", "system").getId();
        transport.publish(
                taskId,
                TaskEvent.Type.APPROVAL_REQUIRED,
                Map.of("status", "WAITING_APPROVAL"));
        assertTrue(Boolean.TRUE.equals(redis.delete(KEY_PREFIX + taskId)));

        List<TaskEvent> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch resumedStepReceived = new CountDownLatch(1);
        StreamTransport.Subscription subscription =
                transport.subscribeWithReplay(taskId, "0", event -> {
                    received.add(event);
                    if (event.type() == TaskEvent.Type.STEP) {
                        resumedStepReceived.countDown();
                    }
                    return true;
                });
        transport.publish(taskId, TaskEvent.Type.STEP, Map.of("step", 4));

        assertAwait(
                resumedStepReceived,
                "A RUNNING task must wait for its replacement Redis stream");
        subscription.close();
        assertEquals(List.of(TaskEvent.Type.STEP), types(received));
    }

    @Test
    void waitingApprovalTaskKeepsSubscriptionForTheResumedRun()
            throws InterruptedException {
        String taskId =
                stateStore.createTask("wait then approve", "system").getId();
        transport.publish(
                taskId,
                TaskEvent.Type.APPROVAL_REQUIRED,
                Map.of("status", "WAITING_APPROVAL"));
        assertEquals(
                1,
                jdbc.update(
                        """
                        UPDATE task
                           SET status = 'WAITING_APPROVAL',
                               owner_id = NULL,
                               lease_expires_at = NULL
                         WHERE id = ?
                        """,
                        taskId));
        assertTrue(Boolean.TRUE.equals(redis.delete(KEY_PREFIX + taskId)));

        List<TaskEvent> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch resumedStepReceived = new CountDownLatch(1);
        StreamTransport.Subscription subscription =
                transport.subscribeWithReplay(taskId, "0", event -> {
                    received.add(event);
                    if (event.type() == TaskEvent.Type.STEP) {
                        resumedStepReceived.countDown();
                    }
                    return true;
                });
        stateStore.markRunning(taskId);
        transport.publish(taskId, TaskEvent.Type.STEP, Map.of("step", 4));

        assertAwait(
                resumedStepReceived,
                "WAITING_APPROVAL remains resumable after its stream is absent");
        subscription.close();
        assertEquals(List.of(TaskEvent.Type.STEP), types(received));
    }

    @Test
    void tokenIsDeliveredLiveButAbsentFromMysqlReplay() throws InterruptedException {
        String taskId = taskId();
        TaskEvent step = transport.publish(taskId, TaskEvent.Type.STEP, Map.of("step", 1));
        List<TaskEvent> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch tokenReceived = new CountDownLatch(1);
        StreamTransport.Subscription subscription = transport.subscribeWithReplay(taskId, step.eventId(), event -> {
            received.add(event);
            tokenReceived.countDown();
            return true;
        });

        transport.publish(taskId, TaskEvent.Type.TOKEN, Map.of("text", "hello"));
        assertAwait(tokenReceived, "Live TOKEN was not delivered");
        subscription.close();

        assertEquals(List.of(TaskEvent.Type.TOKEN), types(received));
        assertNull(received.getFirst().eventId(), "TOKEN must not expose a durable cursor");
        assertEquals(List.of(TaskEvent.Type.STEP), types(eventStore.replayAfter(taskId, 0L)),
                "TOKEN must not be persisted to MySQL event replay");
    }

    @Test
    void terminalEventSetsPositiveStreamTtl() {
        String taskId = taskId();

        transport.publish(taskId, TaskEvent.Type.COMPLETED, Map.of("answer", "done"));

        Long ttlSeconds = redis.getExpire(KEY_PREFIX + taskId, TimeUnit.SECONDS);
        assertTrue(ttlSeconds != null && ttlSeconds > 0,
                () -> "Terminal stream must have a positive TTL, actual=" + ttlSeconds);
    }

    @ParameterizedTest(name = "{0} ends one run without expiring the active task stream")
    @EnumSource(
            value = TaskEvent.Type.class,
            names = {"PAUSED", "APPROVAL_REQUIRED"})
    void runTerminalButTaskNonTerminalEventDoesNotSetStreamTtl(
            TaskEvent.Type type
    ) {
        String taskId = taskId();

        TaskEvent event =
                transport.publish(taskId, type, Map.of("status", type.name()));

        assertTrue(event.isTerminal(), "The current SSE run must end");
        assertEquals(
                -1L,
                redis.getExpire(KEY_PREFIX + taskId, TimeUnit.SECONDS),
                "A resumable task stream must remain live without a TTL");
    }

    @Test
    void missingExpiredStreamFallsBackToDurableMysqlReplay() throws InterruptedException {
        String taskId = taskId();
        transport.publish(taskId, TaskEvent.Type.STEP, Map.of("step", 1));
        transport.publish(taskId, TaskEvent.Type.TOOL_RESULT, Map.of("result", "durable"));
        assertTrue(Boolean.TRUE.equals(redis.delete(KEY_PREFIX + taskId)), "Test stream must exist before expiry");

        List<TaskEvent> received = new ArrayList<>();
        CountDownLatch replayed = new CountDownLatch(2);
        transport.subscribeWithReplay(taskId, "ignored-after-expiry", event -> {
            received.add(event);
            replayed.countDown();
            return true;
        });

        assertAwait(replayed, "Durable MySQL fallback did not replay both events");
        assertEquals(List.of(TaskEvent.Type.STEP, TaskEvent.Type.TOOL_RESULT), types(received));
        assertTrue(received.stream().allMatch(event -> event.eventId() != null));
    }

    private static List<TaskEvent.Type> types(List<TaskEvent> events) {
        return events.stream().map(TaskEvent::type).toList();
    }

    private static void assertAwait(CountDownLatch latch, String message) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), message);
    }

    private static String taskId() {
        return "redis-it-" + UUID.randomUUID();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock redisIntegrationClock() {
            return new MutableClock(TEST_NOW);
        }
    }
}
