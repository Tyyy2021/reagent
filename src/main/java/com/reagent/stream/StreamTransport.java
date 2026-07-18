package com.reagent.stream;

import com.reagent.core.TaskRunToken;

/**
 * 事件流传输层抽象(M7 Stage5)。把"任务产生的事件流"与"看它的 SSE 连接"解耦,且让这层<b>可跨 worker</b>。
 *
 * <p>两实现按 {@code reagent.streaming.transport} 选(各自 {@code @ConditionalOnProperty}):</p>
 * <ul>
 *   <li>{@link TaskEventBus}(in-process,默认):进程内发布订阅 + event 表补播。单 worker / demo 零中间件。</li>
 *   <li>{@link RedisStreamTransport}(redis):Redis Streams 当跨机 live 总线 —— 任意 worker 都能服务任意任务的
 *       stream,客户端连哪台都看得到在别台跑的任务实时流;{@code XREAD from cursor} 让 replay 与 live 是同一个
 *       游标读的连续,天然不漏不重(无需 in-process 那套每任务锁的补播/live handoff)。</li>
 * </ul>
 *
 * <p><b>游标 opaque</b>:{@code cursor} 与 {@link TaskEvent#eventId()} 都是不透明字符串(SSE 的 Last-Event-ID
 * 本就是字符串),由各实现自解释 —— in-process = event 表自增 id 的十进制串;redis = Redis Stream 记录 id
 * ({@code ms-seq})。调用方(Controller / AgentRunner)只依赖本接口,切后端零改动。</p>
 */
public interface StreamTransport {

    /** 一个订阅者;{@code deliver} 返回 false = 该订阅已失效(客户端断开),调用方据此摘除 / 停止补播。 */
    @FunctionalInterface
    interface EventSink {
        boolean deliver(TaskEvent event);
    }

    /** 退订句柄(关闭即退订);窄化 {@link AutoCloseable#close()} 不抛受检异常,方便 try-with-resources。 */
    @FunctionalInterface
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /** 控制面发布事件；运行期事件必须使用 token overload。 */
    TaskEvent publish(String taskId, TaskEvent.Type type, Object data);

    /** 发布运行期事件；非 TOKEN 持久化写受 token fence，TOKEN 只用 token 提供 live 身份。 */
    TaskEvent publish(TaskRunToken token, TaskEvent.Type type, Object data);

    /**
     * 订阅:先补播 cursor 之后的历史、再无缝转 live。
     *
     * @param cursor 客户端 Last-Event-ID(首连 null / 空 = 从头);opaque,由实现自解释。
     */
    Subscription subscribeWithReplay(String taskId, String cursor, EventSink sink);
}
