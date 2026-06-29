package com.reagent.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, Long> {

    /**
     * 补播游标查询:某任务里 id 大于游标的事件,按【自增 id】升序(= 事件发生序)。
     * Stage4 重连续播的核心查询;配合 (task_id, id) 复合索引走范围扫描。
     */
    List<EventEntity> findByTaskIdAndIdGreaterThanOrderByIdAsc(String taskId, Long id);
}
