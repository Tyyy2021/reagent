package com.reagent.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * event 表:任务事件流的【持久化投影】—— M4 Stage4 可恢复 SSE 的 durable 游标来源。
 *
 * <p><b>CQRS 读写分离</b>:{@code message} 表是喂模型、重建上下文的唯一真相源(不变);本表是给
 * 前端 / 客户端的事件流投影。agent 每发一个<b>非 TOKEN</b> 生命周期事件(TASK_STARTED / STEP /
 * TOOL_CALL / TOOL_RESULT / COMPLETED ...)就落一行,自增 {@code id} 即客户端 SSE 的
 * {@code Last-Event-ID} 续播游标——重连时据此精确补播"断点之后"的每个事件。</p>
 *
 * <p>TOKEN 高频易逝(模型逐字输出),<b>不入本表</b>(逐 token 落库太重);故重连补不回"进行中消息"
 * 的 token,但其最终的 ASSISTANT / COMPLETED 等持久事件已落库、会被补播。</p>
 */
@Entity
@Table(name = "event",
        indexes = @Index(name = "idx_event_task_id", columnList = "taskId, id"))
public class EventEntity {

    /** 自增主键 = durable 游标:全表单调,按 task_id 过滤后对单个任务仍严格单调(发生序)。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String taskId;

    /**
     * 事件类型({@link com.reagent.stream.TaskEvent.Type} 的名字)。
     * 刻意存<b>字符串</b>而非映射成原生 enum 列:Hibernate6 对 {@code @Enumerated(STRING)} 会把列按
     * 当时最长枚举值<b>定宽</b>,且 {@code ddl-auto=update} 不会加宽已存在列——日后加枚举值就 "Data truncated"。
     * 存定长 varchar(32) 字符串从根上避开,也让事件表与枚举演进解耦(事件日志本就该向前兼容)。
     */
    @Column(length = 32)
    private String type;

    /** 事件负载 JSON(与 live 在线上字节一致)。length=1_000_000 -> MySQL MEDIUMTEXT,与 message.content 同款,容纳工具结果等较大负载。 */
    @Column(length = 1_000_000)
    private String data;

    private Instant createdAt;

    protected EventEntity() {
    }

    public EventEntity(String taskId, String type, String data) {
        this.taskId = taskId;
        this.type = type;
        this.data = data;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTaskId() { return taskId; }
    public String getType() { return type; }
    public String getData() { return data; }
    public Instant getCreatedAt() { return createdAt; }
}
