package com.reagent.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * task 表:一个被提交的任务(目标 + 状态 + 最终结果)。
 * 这是断点续跑的"骨架"——靠 status 区分哪些任务需要恢复。
 */
@Entity
@Table(name = "task")
public class TaskEntity {

    /** 任务 id,用 UUID 字符串,提交时生成 */
    @Id
    private String id;

    /** 用户给的目标。可能很长,给足长度(>65535 时 MySQL 自动落 LONGTEXT) */
    @Column(length = 1_000_000)
    private String goal;

    // 显式 length=32 让 Hibernate 生成 varchar(32) 而非 MySQL 原生 enum——否则 EnumType.STRING 在 MySQL 上
    // 会建成 enum('RUNNING','COMPLETED','FAILED'),后加 CANCELLED/PAUSED 写入即报 "Data truncated",
    // 且 ddl-auto=update 不改已存在列(与 tool_call.status 同源的坑)。运行库已 ALTER ... MODIFY status VARCHAR(32)。
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private TaskStatus status;

    /** 最终回答;失败时存错误信息 */
    @Column(length = 1_000_000)
    private String result;

    private Instant createdAt;
    private Instant updatedAt;

    /**
     * 已尝试崩溃恢复的次数;超过上限(reagent.recovery.max-attempts)则止损判 FAILED,
     * 避免"确定性崩溃"任务每次启动都被重跑、反复烧 LLM。新列,旧行经 ddl 自动补 0。
     */
    private int recoveryCount;

    /** JPA 要求的无参构造 */
    protected TaskEntity() {
    }

    public static TaskEntity newTask(String goal) {
        TaskEntity t = new TaskEntity();
        t.id = UUID.randomUUID().toString();
        t.goal = goal;
        t.status = TaskStatus.RUNNING;
        t.createdAt = Instant.now();
        t.updatedAt = t.createdAt;
        return t;
    }

    public void complete(String answer) {
        this.status = TaskStatus.COMPLETED;
        this.result = answer;
        this.updatedAt = Instant.now();
    }

    public void fail(String error) {
        this.status = TaskStatus.FAILED;
        this.result = error;
        this.updatedAt = Instant.now();
    }

    /** M4:用户取消(终态)。 */
    public void cancel(String note) {
        this.status = TaskStatus.CANCELLED;
        this.result = note;
        this.updatedAt = Instant.now();
    }

    /** M4:用户暂停(非终态;result 不动,留待 resume 后真正完成时再写)。 */
    public void pause() {
        this.status = TaskStatus.PAUSED;
        this.updatedAt = Instant.now();
    }

    /** M4:PAUSED -> RUNNING(resume 续跑前调用)。 */
    public void markRunning() {
        this.status = TaskStatus.RUNNING;
        this.updatedAt = Instant.now();
    }

    /** 恢复计数 +1,返回新值(这是第几次恢复)。 */
    public int incrementRecovery() {
        this.recoveryCount++;
        this.updatedAt = Instant.now();
        return this.recoveryCount;
    }

    public String getId() { return id; }
    public String getGoal() { return goal; }
    public TaskStatus getStatus() { return status; }
    public String getResult() { return result; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getRecoveryCount() { return recoveryCount; }
}
