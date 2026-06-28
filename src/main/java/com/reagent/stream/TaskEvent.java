package com.reagent.stream;

import java.time.Instant;

/**
 * 任务生命周期事件 —— SSE 流式输出的信封(M4)。
 *
 * <p>Stage1 只发结构化生命周期事件(STEP / TOOL_CALL / ...);信封刻意做成可扩展:
 * Stage2 的 token 级流式加 {@link Type#TOKEN}、Stage3 的打断加 CANCELLED / PAUSED,
 * 都只是多一个枚举值,协议与下游不变。</p>
 *
 * @param taskId 所属任务
 * @param seq    每任务单调递增序号(对应 SSE 的 {@code id:} 行);为 Stage4『Last-Event-ID 重连续播』留缝
 * @param type   事件类型
 * @param data   负载(任意可被 Jackson 序列化的对象,如 Map / String)
 * @param at     产生时刻
 */
public record TaskEvent(String taskId, long seq, Type type, Object data, Instant at) {

    public enum Type {
        TASK_STARTED,   // 任务开跑
        STEP,           // 进入第 N 步
        ASSISTANT,      // 模型一条 assistant 决策(其文本以 TOKEN 增量先行流出)
        TOKEN,          // 模型回答的一个文本增量(Stage2 token 级流式)
        TOOL_CALL,      // 要调某工具
        TOOL_RESULT,    // 某工具返回
        COMPLETED,      // 任务完成(终态)
        FAILED,         // 任务失败(终态)
        CANCELLED,      // 用户取消(终态)
        PAUSED          // 用户暂停(非任务终态,但本次 run 的事件流到此为止)
    }

    /** 便捷工厂:补上当前时刻。 */
    public static TaskEvent of(String taskId, long seq, Type type, Object data) {
        return new TaskEvent(taskId, seq, type, data, Instant.now());
    }

    /** 本次 run 的收尾事件:之后该 run 不再有新事件,SSE 端据此收尾。注意 PAUSED 非任务终态(可 resume),但对"本次 SSE 流"而言已结束。 */
    public boolean isTerminal() {
        return type == Type.COMPLETED || type == Type.FAILED
                || type == Type.CANCELLED || type == Type.PAUSED;
    }
}
