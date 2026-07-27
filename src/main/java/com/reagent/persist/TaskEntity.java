package com.reagent.persist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Column(length = 64)
    private String profileId;

    @Column(length = 1_000_000)
    private String profileSnapshot;

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

    /**
     * ★ M7:分布式租约持有者 —— 当前持有本任务执行权的 worker id(null = 无主)。
     * 启动/扫描时据此区分"别的活 worker 正在跑"与"原 owner 已死、可接管"。新列,旧行经 ddl 自动补 NULL。
     */
    @Column(length = 64)
    private String ownerId;

    /**
     * ★ M7:租约到期时刻。owner 持租期间独占驱动;过期未续租 = 疑似已死,可被其它 worker claim 接管。
     *
     * <p>用 {@code TIMESTAMP_UTC} 强制按 UTC 读写:让租约时间的存取与各 worker 的 {@code serverTimezone}
     * 配置【解耦】—— 无论部署在哪个时区,claim / renew / findRecoverable 都在同一 UTC 基准上比较,杜绝"跨时区
     * worker 把没过期的当过期、或把过期的当没过期"的错位。(普通 datetime 依赖连接时区,曾导致失效扫描漏判。)</p>
     */
    @JdbcTypeCode(SqlTypes.TIMESTAMP_UTC)
    private Instant leaseExpiresAt;

    /**
     * ★ M7:fencing token —— 每次 claim 单调 +1。Stage3 用它把"被接管的旧 owner"栅栏掉:
     * 旧 owner 的写带着过期的 epoch,影响 0 行 → 自知已被接管、干净退出,杜绝双驱动写花状态。新列,旧行补 0。
     */
    private long leaseEpoch;

    /**
     * ★ M7 Stage4:跨 worker 控制信号。任意 worker 收到 cancel/pause 请求、而任务不在本机驱动时写这里,
     * 当前 owner 在安全点读到并执行 —— 让控制面【位置透明】。值:NONE / CANCEL / PAUSE(用 varchar 存,
     * 避开 Hibernate 原生 enum 定宽坑;与 task.status 同源教训)。新列,旧行补 NULL,读时当 NONE。
     */
    @Column(length = 16)
    private String controlSignal;

    /** JPA 要求的无参构造 */
    protected TaskEntity() {
    }

    public static TaskEntity newTask(String goal, Instant now) {
        TaskEntity t = new TaskEntity();
        t.id = UUID.randomUUID().toString();
        t.goal = goal;
        t.status = TaskStatus.RUNNING;
        t.createdAt = now;
        t.updatedAt = t.createdAt;
        return t;
    }

    void freezeProfile(String profileId, String profileSnapshot) {
        this.profileId = profileId;
        this.profileSnapshot = profileSnapshot;
    }

    /** ★ M7:认领租约 —— 新任务出生即归本 worker(owner 一并落库,杜绝"无主 RUNNING"空窗被漏扫)。 */
    public void assignLease(String workerId, Instant expiresAt, Instant now) {
        this.ownerId = workerId;
        this.leaseExpiresAt = expiresAt;
        this.updatedAt = now;
    }

    /** ★ M7:释放租约 —— 进入终态 / 暂停时清空 owner,让任何 worker 可立即接手,也不留陈旧 owner。 */
    public void releaseLease() {
        this.ownerId = null;
        this.leaseExpiresAt = null;
    }

    /** Owner consumes a persisted control request as part of the guarded state transition. */
    public void clearControlSignal() {
        this.controlSignal = "NONE";
    }

    public void complete(String answer, Instant now) {
        this.status = TaskStatus.COMPLETED;
        this.result = answer;
        this.updatedAt = now;
        releaseLease();
    }

    public void fail(String error, Instant now) {
        this.status = TaskStatus.FAILED;
        this.result = error;
        this.updatedAt = now;
        releaseLease();
    }

    /** M4:用户取消(终态)。 */
    public void cancel(String note, Instant now) {
        this.status = TaskStatus.CANCELLED;
        this.result = note;
        this.updatedAt = now;
        releaseLease();
    }

    /** M4:用户暂停(非终态;result 不动,留待 resume 后真正完成时再写)。 */
    public void pause(Instant now) {
        this.status = TaskStatus.PAUSED;
        this.updatedAt = now;
        releaseLease();
    }

    /** M4:PAUSED -> RUNNING(resume 续跑前调用)。 */
    public void markRunning(Instant now) {
        this.status = TaskStatus.RUNNING;
        this.updatedAt = now;
    }

    /** 恢复计数 +1,返回新值(这是第几次恢复)。 */
    public int incrementRecovery(Instant now) {
        this.recoveryCount++;
        this.updatedAt = now;
        return this.recoveryCount;
    }

    public String getId() { return id; }
    public String getGoal() { return goal; }
    public String getProfileId() { return profileId; }
    public String getProfileSnapshot() { return profileSnapshot; }
    public TaskStatus getStatus() { return status; }
    public String getResult() { return result; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getRecoveryCount() { return recoveryCount; }
    public String getOwnerId() { return ownerId; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public long getLeaseEpoch() { return leaseEpoch; }
    public String getControlSignal() { return controlSignal; }
}
