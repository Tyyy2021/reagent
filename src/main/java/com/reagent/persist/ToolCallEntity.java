package com.reagent.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * tool_call 表:工具调用的【幂等账本】。
 *
 * 主键直接用模型给的 tool_call_id —— 它在一次任务里全局唯一,且恢复时我们重放的是
 * 已落库的同一条 assistant 消息(不重新问模型),所以这个 id 跨重启是稳定的。
 *
 * 用它做幂等:执行某次工具前先查这张表,若已 DONE 就复用 result、跳过执行。
 *
 * 诚实地讲清边界:真正"恰好一次"需要工具下游也按 idempotency_key 去重。
 * 这里覆盖的是最常见的崩溃窗口——【副作用已发生、但结果消息还没落库】时重启不重复执行。
 * 至于"副作用执行到一半就崩"这种窗口,本质是 at-least-once;只读工具(list_dir / read_file)天然安全。
 */
@Entity
@Table(name = "tool_call")
public class ToolCallEntity {

    /** = 模型返回的 tool_call_id */
    @Id
    private String id;

    private String taskId;

    private String toolName;

    private Integer assistantMessageSeq;

    @Column(length = 1_000_000)
    private String arguments;

    @Column(length = 1_000_000)
    private String result;

    // 显式 length=32:EnumType.STRING 默认按"建表时最长枚举值"定宽(早期 PENDING/DONE → varchar(7)),
    // 后加 IN_PROGRESS/IN_DOUBT 会超宽被截断,而 ddl-auto=update 不会加宽已存在的列。给足余量、并显式声明意图。
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ToolCallStatus status;

    private Instant createdAt;
    private Instant completedAt;

    /** 这条 tool_call 被发起执行的次数(每次 markInProgress +1);用于 per-tool 重试上限止损 */
    private int attemptCount;

    /** 最近一次开始执行的时刻(markInProgress 时写);null = 还从没开跑过 */
    private Instant startedAt;

    protected ToolCallEntity() {
    }

    /** 新建一条待执行(PENDING)记录 */
    public ToolCallEntity(String id, String taskId, String toolName, String arguments) {
        this(id, taskId, toolName, arguments, Instant.EPOCH);
    }

    public ToolCallEntity(String id, String taskId, String toolName, String arguments, Instant createdAt) {
        this(id, taskId, toolName, arguments, createdAt, null);
    }

    public ToolCallEntity(String id, String taskId, String toolName, String arguments,
                          Instant createdAt, Integer assistantMessageSeq) {
        this.id = id;
        this.taskId = taskId;
        this.toolName = toolName;
        this.assistantMessageSeq = assistantMessageSeq;
        this.arguments = arguments;
        this.status = ToolCallStatus.PENDING;
        this.createdAt = createdAt;
    }

    public void markDone(String result) {
        markDone(result, timestampFallback());
    }

    public void markDone(String result, Instant now) {
        this.result = result;
        this.status = ToolCallStatus.DONE;
        this.completedAt = now;
    }

    /**
     * 进入"在途"——必须在执行副作用<b>之前</b> commit。把"这次尝试开始了"变成持久事实,
     * 让恢复时能区分"PENDING=从没开跑"与"IN_PROGRESS=可能做了一半 / 跑完没记"。
     */
    public void markInProgress() {
        markInProgress(timestampFallback());
    }

    public void markInProgress(Instant now) {
        this.status = ToolCallStatus.IN_PROGRESS;
        this.attemptCount++;
        this.startedAt = now;
    }

    /**
     * 标记为"结果存疑"——非幂等工具崩在 in-doubt 窗口、又无 journal 完成记录时:副作用是否发生不可知,
     * 系统不自动重试,把这条"未知"的观察(result)回给模型,由其核对 / 重发。
     */
    public void markInDoubt(String result) {
        markInDoubt(result, timestampFallback());
    }

    public void markInDoubt(String result, Instant now) {
        this.result = result;
        this.status = ToolCallStatus.IN_DOUBT;
        this.completedAt = now;
    }

    private Instant timestampFallback() {
        return startedAt != null ? startedAt : createdAt;
    }

    public String getId() { return id; }
    public String getTaskId() { return taskId; }
    public String getToolName() { return toolName; }
    public Integer getAssistantMessageSeq() { return assistantMessageSeq; }
    public String getArguments() { return arguments; }
    public String getResult() { return result; }
    public ToolCallStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getStartedAt() { return startedAt; }
}
