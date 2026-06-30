package com.reagent.stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内、按 taskId 的事件发布订阅(M4)—— {@link StreamTransport} 的 <b>in-process 实现</b>(默认启用,
 * 单 worker / demo 零中间件)。M7 Stage5 起与 {@link RedisStreamTransport} 二选一(按 reagent.streaming.transport)。
 *
 * <p>publish 来自 agent 的虚拟线程(每任务单线程、串行);subscribe / 退订来自 Tomcat 的 servlet 线程。
 * 任务不绑连接:没人看也照跑、多个人看则多播。</p>
 *
 * <p><b>可恢复(M4 Stage4):</b>非 TOKEN 事件经 {@link EventStore} 持久化成一行(自增 id = durable 游标);
 * {@link #subscribeWithReplay} 先从 event 表补播 {@code id > 游标} 的历史、再挂 live sink。关键:<b>{补播读取 +
 * 挂载}</b> 与 <b>{持久化 + 多播}</b> 共用<b>每任务一把锁</b>,故二者不交错——任一事件要么落补播、要么走 live,
 * 绝不漏(无缝)也绝不重(无需去重)。稳态只有该任务自己的虚拟线程碰锁 = 无竞争;只有"重连那一刻"Tomcat 线程才短暂争用。</p>
 *
 * <p>TOKEN 高频易逝 → 不落库、不带 durable id(live-only);重连补不回"进行中"的 token,但其最终 ASSISTANT /
 * COMPLETED 等持久事件会被补播。<b>跨 worker 场景见 {@link RedisStreamTransport}</b>(Redis Streams 当 live 总线)。</p>
 */
@Component
@ConditionalOnProperty(name = "reagent.streaming.transport", havingValue = "in-process", matchIfMissing = true)
public class TaskEventBus implements StreamTransport {

    private final EventStore eventStore;

    /** taskId -> 该任务当前订阅者。subscribe 建条目、最后一个退订删条目;publish 不建条目(无订阅者=零开销、无泄漏)。 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<EventSink>> subscribers = new ConcurrentHashMap<>();

    /** taskId -> 每任务一把锁:串行化 {publish 的 持久化+多播} 与 {subscribeWithReplay 的 补播+挂载},消解补播/live 交错。 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public TaskEventBus(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    private Object lockFor(String taskId) {
        return locks.computeIfAbsent(taskId, k -> new Object());
    }

    @Override
    public TaskEvent publish(String taskId, TaskEvent.Type type, Object data) {
        Object lock = lockFor(taskId);
        synchronized (lock) {
            // 非 TOKEN 先落 event 表,拿回行 id(十进制串)作 durable 游标(在多播【之前】commit);TOKEN 高频易逝 -> 不落库、eventId=null
            String eventId = (type == TaskEvent.Type.TOKEN) ? null : String.valueOf(eventStore.append(taskId, type, data));
            TaskEvent event = TaskEvent.of(taskId, eventId, type, data);
            multicast(taskId, event);
            if (event.isTerminal()) {
                locks.remove(taskId, lock);   // 终态回收(同一把锁才删,防误删并发刚新建的)
            }
            return event;
        }
    }

    /** 推给该任务当前所有订阅者;失效的(deliver=false / 抛异常)就地摘除,不连累其它。须在持锁下调用。 */
    private void multicast(String taskId, TaskEvent event) {
        CopyOnWriteArrayList<EventSink> list = subscribers.get(taskId);
        if (list == null) {
            return;
        }
        for (EventSink sink : list) {
            boolean ok;
            try {
                ok = sink.deliver(event);
            } catch (RuntimeException ex) {
                ok = false;   // 防御:sink 本不该抛,抛了也当失效摘除
            }
            if (!ok) {
                list.remove(sink);
            }
        }
    }

    @Override
    public Subscription subscribeWithReplay(String taskId, String cursor, EventSink sink) {
        long afterEventId = parseCursor(cursor);
        Object lock = lockFor(taskId);
        synchronized (lock) {
            for (TaskEvent past : eventStore.replayAfter(taskId, afterEventId)) {
                boolean ok;
                try {
                    ok = sink.deliver(past);
                } catch (RuntimeException ex) {
                    ok = false;
                }
                if (!ok) {
                    return () -> { };   // 客户端已断:不挂 live,返回 no-op 退订句柄
                }
            }
            return subscribe(taskId, sink);
        }
    }

    /** 挂 live sink;返回的 {@link Subscription} 关闭即退订。须在持锁下经 {@link #subscribeWithReplay} 调用。 */
    private Subscription subscribe(String taskId, EventSink sink) {
        subscribers.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(sink);
        return () -> {
            CopyOnWriteArrayList<EventSink> list = subscribers.get(taskId);
            if (list != null) {
                list.remove(sink);
                if (list.isEmpty()) {
                    subscribers.remove(taskId, list);  // 空条目回收;remove(k,v) 防误删并发刚新建的
                }
            }
        };
    }

    /** opaque 游标 → event 表自增 id;首连 / 空 / 脏值都退回 0(= 从头补播)。 */
    private static long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
