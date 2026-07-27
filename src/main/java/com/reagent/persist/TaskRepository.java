package com.reagent.persist;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TaskEntity t where t.id = :id")
    Optional<TaskEntity> findByIdForUpdate(@Param("id") String id);

    /** 崩溃恢复用:启动时找出所有"卡在进行中"的任务 */
    List<TaskEntity> findByStatus(TaskStatus status);

    /**
     * ★ M7 Stage2:失效扫描 —— 找出 RUNNING 且租约已过期的孤儿任务(原 owner 已停止心跳)。
     * {@code leaseExpiresAt < now}:owner=null 的新任务(lease=null,NULL 比较为 unknown)天然不会被选中。
     * 用 {@link Limit} 限批,避免一个 worker 一次抢太多。
     */
    @Query("""
           select t from TaskEntity t
            where t.status = com.reagent.persist.TaskStatus.RUNNING
              and (t.ownerId is null or t.leaseExpiresAt < :now)
            order by t.updatedAt asc
           """)
    List<TaskEntity> findRecoverable(@Param("now") Instant now, Limit limit);

    /**
     * ★ M7 Stage1:原子 claim —— 抢占某任务的执行租约。
     *
     * <p>单条条件 UPDATE = 跨进程的 compare-and-set(靠 MySQL 行锁保证原子):仅当任务仍 {@code RUNNING}、
     * 且【无主 / 是我自己 / 租约已过期】时才占得到,占到就把 owner 设为我、续租到 {@code expires}、epoch+1。
     * 两个 worker 同时抢同一任务,DB 行锁让 UPDATE 串行化 —— 只有一个能让 {@code WHERE} 成立、影响 1 行。</p>
     *
     * <ul>
     *   <li>{@code owner_id IS NULL}:全新任务 / M7 之前的历史旧行;</li>
     *   <li>{@code owner_id = :me}:我自己崩前持有的 —— 重启后【秒认领】,不必等 TTL 过期;</li>
     *   <li>{@code lease_expires_at < :now}:原 owner 已停止续租(崩溃 / 网络分区)→ 失败转移在此接管。</li>
     * </ul>
     *
     * @return 1 = 抢到(可驱动);0 = 没抢到(别人持活租约,应跳过)。
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE TaskEntity t
               SET t.ownerId = :me,
                   t.leaseExpiresAt = :expires,
                   t.leaseEpoch = t.leaseEpoch + 1
             WHERE t.id = :id
               AND t.status = com.reagent.persist.TaskStatus.RUNNING
               AND (t.ownerId IS NULL OR t.ownerId = :me OR t.leaseExpiresAt < :now)
            """)
    int claim(@Param("id") String id,
              @Param("me") String me,
              @Param("now") Instant now,
              @Param("expires") Instant expires);

    /**
     * ★ M7 Stage2/3:心跳续租 —— 仅当任务仍归我({@code owner_id = :me})【且 epoch 仍是我持有的那次】才续。
     * 不动 owner、不动 epoch(续租不改所有权)。返回 0 = 已被别的 worker claim 接管(epoch 已 +1)→ 调用方据此 fence。
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE TaskEntity t SET t.leaseExpiresAt = :expires
             WHERE t.id = :id AND t.ownerId = :me AND t.leaseEpoch = :epoch
            """)
    int renew(@Param("id") String id, @Param("me") String me, @Param("epoch") long epoch, @Param("expires") Instant expires);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE TaskEntity t
               SET t.status = com.reagent.persist.TaskStatus.WAITING_APPROVAL,
                   t.ownerId = null,
                   t.leaseExpiresAt = null,
                   t.updatedAt = :now
             WHERE t.id = :id
               AND t.status = com.reagent.persist.TaskStatus.RUNNING
               AND t.ownerId = :workerId
               AND t.leaseEpoch = :epoch
            """)
    int waitForApproval(
            @Param("id") String id,
            @Param("workerId") String workerId,
            @Param("epoch") long epoch,
            @Param("now") Instant now);

    /**
     * ★ M7 Stage4:跨 worker 下达控制信号 —— 仅对仍在跑(RUNNING)的任务有意义(由其 owner 在安全点消费)。
     * @return 1 = 已记录;0 = 任务不在 RUNNING(已结束 / 暂停等),调用方据此回 409。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.controlSignal = :sig WHERE t.id = :id AND t.status = com.reagent.persist.TaskStatus.RUNNING")
    int requestControl(@Param("id") String id, @Param("sig") String sig);

    /** ★ M7 Stage4:owner 消费控制信号后清回 NONE(避免 pause→resume 后又被重复触发)。 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE TaskEntity t SET t.controlSignal = 'NONE' WHERE t.id = :id")
    int clearControlSignal(@Param("id") String id);

    /** ★ M7 Stage4:只取控制信号列(owner 每个安全点轻量读,不取整行)。 */
    @Query("SELECT t.controlSignal FROM TaskEntity t WHERE t.id = :id")
    String findControlSignal(@Param("id") String id);
}
