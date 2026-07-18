package com.reagent.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    /**
     * 按【自增主键 id】升序取出某任务的全部消息,用于重建上下文。
     * 用 id 而非 seq 排序:id 是 PK,天然唯一且严格按插入单调,排序正确性不依赖 seq 是否算对
     * (把脆弱的 count-based seq 移出正确性路径)。
     */
    List<MessageEntity> findByTaskIdOrderByIdAsc(String taskId);

    /** 必须在持有对应 task 行锁时调用,生成稠密且唯一的任务内序号。 */
    @Query("select coalesce(max(m.seq), -1) + 1 from MessageEntity m where m.taskId = :taskId")
    int nextSequenceForLockedTask(@Param("taskId") String taskId);

    long countByTaskId(String taskId);
}
