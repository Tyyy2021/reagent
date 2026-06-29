package com.reagent.stream;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内、按 taskId 的事件发布订阅 —— 把"正在跑的任务"与"看它的 SSE 连接"解耦(M4)。
 *
 * <p>publish 来自 agent 的虚拟线程(每任务单线程、串行);subscribe / 退订来自 Tomcat 的 servlet 线程。
 * 任务不绑连接:没人看也照跑、多个人看则多播。</p>
 *
 * <p><b>Stage4 可恢复:</b>非 TOKEN 事件经 {@link EventStore} 持久化成一行(自增 id = durable 游标);
 * 重连时 {@link #subscribeWithReplay} 先从 event 表补播 {@code id > 游标} 的历史事件、再挂 live sink。
 * 关键:<b>{补播读取 + 挂载}</b> 与 <b>{持久化 + 多播}</b> 共用<b>每任务一把锁</b>,故二者不会交错——
 * 任一事件要么落在补播里、要么走 live,绝不漏(无缝衔接)也绝不重(无需去重)。稳态下只有该任务自己的
 * 虚拟线程碰这把锁 = 无竞争;只有"重连那一刻"Tomcat 线程才短暂争用。</p>
 *
 * <p>TOKEN 高频易逝 → 不落库、不带 durable id(live-only);重连补不回"进行中消息"的 token,但其最终的
 * ASSISTANT / COMPLETED 等持久事件会被补播。M7 多 worker 时把这层换 Redis pub/sub + 共享 event 表,接口不变。</p>
 */
@Component
public class TaskEventBus {

    /**
     * 一个订阅者。{@code deliver} 返回 {@code false} 表示该订阅已失效(客户端断开):
     * live 多播时据此就地摘除、补播时据此停止且不挂 live。用返回值而非异常表达"失效",控制流更清晰。
     */
    @FunctionalInterface
    public interface EventSink {
        boolean deliver(TaskEvent event);
    }

    /** 退订句柄(关闭即退订);窄化 {@link AutoCloseable#close()} 不抛受检异常,方便 try-with-resources。 */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

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

    /**
     * 持久化(非 TOKEN)+ 组装事件 + 推给该任务所有订阅者。全程持每任务锁,与 {@link #subscribeWithReplay}
     * 互斥,保证重连补播与 live 不漏不重。
     *
     * @return 组装好的事件(便于调用方顺带记日志)
     */
    public TaskEvent publish(String taskId, TaskEvent.Type type, Object data) {
        Object lock = lockFor(taskId);
        synchronized (lock) {
            // 非 TOKEN 先落 event 表,拿回行 id 作 durable 游标(在多播【之前】commit:客户端拿到的 id 一定指向已持久化的行);
            // TOKEN 高频易逝 -> 不落库、eventId=null(live-only)
            Long eventId = (type == TaskEvent.Type.TOKEN) ? null : eventStore.append(taskId, type, data);
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

    /**
     * <b>Stage4 续播订阅:</b>原子地"先补播 {@code id > 游标} 的历史事件、再挂 live sink"——全程持每任务锁,
     * publish 被挡住,故补播与 live 之间无缝(不漏)且不重叠(不重)。补播事件经【与 live 完全相同的 sink】发出,
     * 实现 replay/live 同构。补播途中 sink 返回 false(客户端已断)则停止且不挂 live、返回 no-op 退订句柄。
     *
     * @param afterEventId 客户端 Last-Event-ID 游标(首连传 0 = 从头补播)
     */
    public Subscription subscribeWithReplay(String taskId, long afterEventId, EventSink sink) {
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
}
