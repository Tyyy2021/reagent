package com.reagent.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<MessageEntity, Long> {

    /**
     * 按【自增主键 id】升序取出某任务的全部消息,用于重建上下文。
     * 用 id 而非 seq 排序:id 是 PK,天然唯一且严格按插入单调,排序正确性不依赖 seq 是否算对
     * (把脆弱的 count-based seq 移出正确性路径)。
     */
    List<MessageEntity> findByTaskIdOrderByIdAsc(String taskId);

    /** 仅用于生成人类可读的 seq 序号(非排序依据);seq 唯一性另由 (task_id, seq) 唯一约束兜底 */
    int countByTaskId(String taskId);
}
