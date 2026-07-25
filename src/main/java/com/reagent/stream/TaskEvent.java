package com.reagent.stream;

import java.time.Instant;

/**
 * 任务生命周期事件 —— SSE 流式输出的信封(M4)。
 *
 * <p>承载结构化生命周期事件(STEP / TOOL_CALL / ...)与 token 级增量(TOKEN)。信封刻意可扩展:
 * Stage2 加 {@link Type#TOKEN}、Stage3 加 CANCELLED / PAUSED,都只多一个枚举值,协议不变。</p>
 *
 * @param taskId  所属任务
 * @param eventId <b>durable 游标(opaque 字符串)</b>:作 SSE {@code id:} 行、客户端 {@code Last-Event-ID}
 *                续播游标,由 transport 自解释 —— in-process = {@code event} 表自增行 id 的十进制串;
 *                redis(M7 Stage5)= Redis Stream 记录 id({@code ms-seq})。TOKEN 等 live-only 事件
 *                = {@code null}(高频易逝、不落库、不带 id)。M7 Stage5 从 {@code Long} 改 {@code String}:
 *                Redis Stream id 非整型,opaque 串能统一两种后端;in-process 内部仍按十进制解析回自增 id。
 * @param type    事件类型
 * @param data    负载(任意可被 Jackson 序列化的对象,如 Map / String)
 * @param at      产生时刻
 */
public record TaskEvent(String taskId, String eventId, Type type, Object data, Instant at) {

    public enum Type {
        TASK_STARTED,   // 任务开跑
        STEP,           // 进入第 N 步
        ASSISTANT,      // 模型一条 assistant 决策(其文本以 TOKEN 增量先行流出)
        TOKEN,          // 模型回答的一个文本增量(Stage2 token 级流式;live-only,不落库)
        TOOL_CALL,      // 要调某工具
        TOOL_RESULT,    // 某工具返回
        KNOWLEDGE_RETRIEVED, // RAG citations retrieved; event data is metadata-only
        APPROVAL_REQUIRED, // durable task state is WAITING_APPROVAL; this event ends the current run
        COMPLETED,      // 任务完成(终态)
        FAILED,         // 任务失败(终态)
        CANCELLED,      // 用户取消(终态)
        PAUSED          // 用户暂停(非任务终态,但本次 run 的事件流到此为止)
    }

    /** live 事件工厂:持久事件传游标 id,TOKEN 传 {@code null};时间由统一 UTC Clock 显式提供。 */
    public static TaskEvent of(String taskId, String eventId, Type type, Object data, Instant at) {
        return new TaskEvent(taskId, eventId, type, data, at);
    }

    /** 本次 run 的收尾事件:之后该 run 不再有新事件,SSE 端据此收尾。注意 PAUSED 非任务终态(可 resume),但对"本次 SSE 流"而言已结束。 */
    public boolean isTerminal() {
        return type == Type.COMPLETED || type == Type.FAILED
                || type == Type.CANCELLED || type == Type.PAUSED
                || type == Type.APPROVAL_REQUIRED;
    }
}
