package com.reagent.stream;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 进程内、按 taskId 的事件发布订阅 —— 把"正在跑的任务"与"看它的 SSE 连接"解耦(M4)。
 *
 * <p>publish 来自 agent 的虚拟线程,subscribe / 退订来自 Tomcat 的 servlet 线程,
 * 用并发容器兜住跨线程。任务不绑连接:没人看也照跑、多个人看则多播。</p>
 *
 * <p>只承载<b>实时</b>事件;终态后的"补播 / 对账"由 DB({@code StateStore})兜底——
 * 晚来的订阅者从库里读当前状态,不依赖本总线保留历史。Stage4 的『Last-Event-ID
 * 重连续播』会用 message 自增 id 做持久游标,届时在此之上加一层即可。</p>
 *
 * <p>M7 多 worker 时把这层实现换成 Redis pub/sub,对外接口不变。</p>
 */
@Component
public class TaskEventBus {

    /** taskId -> 该任务当前订阅者。subscribe 建条目、最后一个退订删条目;publish 不建条目(无订阅者=零开销、无泄漏)。 */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<TaskEvent>>> subscribers = new ConcurrentHashMap<>();

    /** taskId -> 单调 seq 计数器(SSE id: 行;Stage4 重连续播的留缝)。 */
    private final ConcurrentHashMap<String, AtomicLong> seqs = new ConcurrentHashMap<>();

    /**
     * 生成单调 seq、组装事件、推给该任务所有订阅者。
     * 单个订阅者抛异常不影响其它(多半是客户端断开,就地摘除)。
     *
     * @return 组装好的事件(便于调用方顺带记日志)
     */
    public TaskEvent publish(String taskId, TaskEvent.Type type, Object data) {
        long seq = seqs.computeIfAbsent(taskId, k -> new AtomicLong()).incrementAndGet();
        TaskEvent event = TaskEvent.of(taskId, seq, type, data);

        CopyOnWriteArrayList<Consumer<TaskEvent>> list = subscribers.get(taskId);
        if (list != null) {
            for (Consumer<TaskEvent> sink : list) {
                try {
                    sink.accept(event);
                } catch (RuntimeException ex) {
                    list.remove(sink); // 连接已坏(多半客户端断开),摘掉,不连累其它订阅者
                }
            }
        }

        if (event.isTerminal()) {
            seqs.remove(taskId); // 终态:序号计数器回收(Stage4 的持久游标用 message 自增 id,不靠它)
        }
        return event;
    }

    /** 订阅某任务的实时事件;返回的 {@link Subscription} 关闭即退订。 */
    public Subscription subscribe(String taskId, Consumer<TaskEvent> sink) {
        subscribers.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(sink);
        return () -> {
            CopyOnWriteArrayList<Consumer<TaskEvent>> list = subscribers.get(taskId);
            if (list != null) {
                list.remove(sink);
                if (list.isEmpty()) {
                    subscribers.remove(taskId, list); // 空条目回收;remove(k,v) 防误删并发刚新建的
                }
            }
        };
    }

    /** 退订句柄(关闭即退订);窄化 {@link AutoCloseable#close()} 不抛受检异常,方便 try-with-resources。 */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
