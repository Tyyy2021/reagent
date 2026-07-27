package com.reagent.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * message 表:对话历史里的一条消息 —— 这是【重建上下文的唯一真相源】。
 *
 * agent 每往 Context 里追加一条消息(system / user / assistant / tool),就在这里落一行。
 * 崩溃恢复时,按【自增主键 id】顺序把这些行读出来,就能 1:1 还原出发给模型的 messages 数组,
 * 接着往下跑(用 id 排序:它是 PK,天然唯一且严格按插入单调,正确性不依赖 seq 是否算对)。
 *
 * 一条消息可能是:
 *  - system / user                : 只有 role + content
 *  - assistant(普通最终回答)      : role + content
 *  - assistant(要调工具)          : role + content(可能为空) + toolCallsJson(原始 tool_calls 数组)
 *  - tool(工具结果)               : role + content + toolCallId(对应是哪次调用)
 */
@Entity
@Table(name = "message",
        uniqueConstraints = @UniqueConstraint(name = "uk_msg_task_seq", columnNames = {"task_id", "seq"}))
public class MessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String taskId;

    /**
     * 同一任务内的顺序号(从 0 稠密递增)。
     * 注意:重放排序以自增 {@code id} 为准,seq 不再扛排序正确性——它只作人类可读序号,
     * 并配 (task_id, seq) 唯一约束当"同一任务被并发驱动"的 fail-fast 探针(正常永不触发)。
     */
    private int seq;

    private String role;

    @Column(length = 1_000_000)
    private String content;

    /** 仅 assistant 要调工具时有值:原样保存的 tool_calls JSON 数组,用于精确重放 */
    @Column(length = 1_000_000)
    private String toolCallsJson;

    /** 仅 tool 消息有值:这条结果对应哪一次工具调用 */
    private String toolCallId;

    private Instant createdAt;

    protected MessageEntity() {
    }

    public MessageEntity(String taskId, int seq, String role,
                         String content, String toolCallsJson, String toolCallId,
                         Instant createdAt) {
        this.taskId = taskId;
        this.seq = seq;
        this.role = role;
        this.content = content;
        this.toolCallsJson = toolCallsJson;
        this.toolCallId = toolCallId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getTaskId() { return taskId; }
    public int getSeq() { return seq; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getToolCallsJson() { return toolCallsJson; }
    public String getToolCallId() { return toolCallId; }
    public Instant getCreatedAt() { return createdAt; }
}
