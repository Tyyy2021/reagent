package com.reagent.stream;

import com.reagent.core.TaskRunToken;

import java.util.List;

/**
 * 事件日志存储 —— Stage4 把每个(非 TOKEN)事件持久化成一行,自增 id 作 durable 游标。
 *
 * <p>抽象成接口有两层意义:① 单测可注入内存假实现,脱离 DB 验证 {@link TaskEventBus} 的补播 / 不漏不重逻辑;
 * ② M7 多 worker 时可换 Redis Stream / Kafka 等持久流后端,对 {@link TaskEventBus} 透明。</p>
 */
public interface EventStore {

    /** 控制面追加事件,返回 durable 行 id；运行期事件必须使用 {@link #appendFenced}。 */
    long append(String taskId, TaskEvent.Type type, Object data);

    /** 运行期追加事件；旧 epoch 在插入前被拒绝。 */
    long appendFenced(TaskRunToken token, TaskEvent.Type type, Object data);

    /** 补播:取出某任务里 id 大于游标的历史事件,重建成与 live 完全同构的 {@link TaskEvent}(按发生序)。 */
    List<TaskEvent> replayAfter(String taskId, long afterEventId);
}
