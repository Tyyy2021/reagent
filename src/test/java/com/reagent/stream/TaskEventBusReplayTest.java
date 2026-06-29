package com.reagent.stream;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TaskEventBus} 续播逻辑单测(M4 Stage4)。注入内存假 {@link EventStore},脱离 DB 验证总线的
 * "先补播 id&gt;游标 的历史、再无缝转 live"以及最值钱的【不漏不重】并发不变量。
 *
 * <p>DB 层(event 表读写)的正确性由 e2e 兜底,与本项目既有风格一致(持久层不写 DB 单测)。</p>
 */
class TaskEventBusReplayTest {

    /** 内存假 EventStore:全表自增 id;replayAfter 按 taskId + id>游标 过滤、按 id 升序(同 JPA 实现语义)。 */
    private static final class FakeEventStore implements EventStore {
        private record Row(long id, String taskId, TaskEvent.Type type, Object data) { }

        private final List<Row> rows = new ArrayList<>();
        private long nextId = 0;

        @Override
        public synchronized long append(String taskId, TaskEvent.Type type, Object data) {
            long id = ++nextId;
            rows.add(new Row(id, taskId, type, data));
            return id;
        }

        @Override
        public synchronized List<TaskEvent> replayAfter(String taskId, long afterEventId) {
            List<TaskEvent> out = new ArrayList<>();
            for (Row r : rows) {
                if (r.taskId().equals(taskId) && r.id() > afterEventId) {
                    out.add(new TaskEvent(taskId, r.id(), r.type(), r.data(), Instant.now()));
                }
            }
            return out;
        }
    }

    private static List<Long> ids(List<TaskEvent> events) {
        List<Long> out = new ArrayList<>();
        for (TaskEvent e : events) {
            out.add(e.eventId());
        }
        return out;
    }

    @Test
    void 补播游标之后的历史再无缝转live_顺序与id都对() {
        TaskEventBus bus = new TaskEventBus(new FakeEventStore());
        bus.publish("t", TaskEvent.Type.STEP, Map.of("step", 1));   // id 1
        bus.publish("t", TaskEvent.Type.STEP, Map.of("step", 2));   // id 2
        bus.publish("t", TaskEvent.Type.STEP, Map.of("step", 3));   // id 3

        List<TaskEvent> got = Collections.synchronizedList(new ArrayList<>());
        TaskEventBus.Subscription sub = bus.subscribeWithReplay("t", 1, e -> {
            got.add(e);
            return true;
        });
        assertEquals(List.of(2L, 3L), ids(got), "cursor=1:补播应只给 id>1 的历史");

        bus.publish("t", TaskEvent.Type.STEP, Map.of("step", 4));   // live, id 4
        assertEquals(List.of(2L, 3L, 4L), ids(got), "补播后无缝转 live");

        sub.close();
        bus.publish("t", TaskEvent.Type.STEP, Map.of("step", 5));   // 已退订
        assertEquals(List.of(2L, 3L, 4L), ids(got), "退订后不再收到");
    }

    @Test
    void TOKEN不落库_eventId为null_且不被补播() {
        FakeEventStore store = new FakeEventStore();
        TaskEventBus bus = new TaskEventBus(store);

        List<TaskEvent> live = Collections.synchronizedList(new ArrayList<>());
        bus.subscribeWithReplay("t", 0, e -> {
            live.add(e);
            return true;
        });
        bus.publish("t", TaskEvent.Type.STEP, Map.of("step", 1));      // 持久, id 1
        bus.publish("t", TaskEvent.Type.TOKEN, Map.of("text", "hi"));  // live-only

        assertEquals(2, live.size(), "live 两个都收到");
        assertEquals(1L, live.get(0).eventId());
        assertNull(live.get(1).eventId(), "TOKEN 不带 durable id");
        assertEquals(TaskEvent.Type.TOKEN, live.get(1).type());

        // 新订阅 cursor=0:只补播到持久的 STEP(TOKEN 没落库)
        List<TaskEvent> replay = new ArrayList<>();
        bus.subscribeWithReplay("t", 0, e -> {
            replay.add(e);
            return true;
        });
        assertEquals(List.of(1L), ids(replay), "TOKEN 不入 event 表 -> 不被补播");
    }

    @Test
    void 并发发布与中途重连_不漏不重_恰好覆盖游标之后全部() throws Exception {
        for (int round = 0; round < 30; round++) {       // 反复跑探不同交错
            TaskEventBus bus = new TaskEventBus(new FakeEventStore());
            int n = 200;
            List<TaskEvent> got = Collections.synchronizedList(new ArrayList<>());

            CountDownLatch publishedSome = new CountDownLatch(1);
            Thread publisher = new Thread(() -> {
                for (int i = 1; i <= n; i++) {
                    bus.publish("t", TaskEvent.Type.STEP, Map.of("i", i));
                    if (i == 20) {
                        publishedSome.countDown();        // 放行订阅 = 制造"补播 + live"重叠
                    }
                }
            });
            publisher.start();
            publishedSome.await();
            // cursor=0 中途加入:补播拿到此刻已 commit 的 1..m,其余 m+1..n 走 live
            TaskEventBus.Subscription sub = bus.subscribeWithReplay("t", 0, e -> {
                got.add(e);
                return true;
            });
            publisher.join();
            sub.close();

            // 不变量:收到的 id 恰为 1..n 各一次、严格升序(锁保证不漏不重)
            List<Long> ids = ids(got);
            assertEquals(n, ids.size(), "round " + round + ":期望 " + n + " 个,实得 " + ids.size());
            for (int i = 0; i < n; i++) {
                assertEquals((long) (i + 1), ids.get(i), "round " + round + ":第 " + i + " 个 id 不连续(漏或重)");
            }
        }
    }

    @Test
    void 补播途中客户端断开_停止补播且不挂live() {
        TaskEventBus bus = new TaskEventBus(new FakeEventStore());
        for (int i = 1; i <= 5; i++) {
            bus.publish("t", TaskEvent.Type.STEP, Map.of("i", i));    // id 1..5
        }

        List<TaskEvent> got = new ArrayList<>();
        TaskEventBus.Subscription sub = bus.subscribeWithReplay("t", 0, e -> {
            got.add(e);
            return got.size() < 2;     // 收到第 2 个就"断开"(返回 false)
        });
        assertEquals(2, got.size(), "补播在第 2 个就停,不继续补播 3..5");

        bus.publish("t", TaskEvent.Type.STEP, Map.of("i", 6));        // 不应到达(没挂 live)
        assertEquals(2, got.size(), "客户端断开 -> 没挂 live sink");
        sub.close();                                                  // no-op,安全
    }
}
