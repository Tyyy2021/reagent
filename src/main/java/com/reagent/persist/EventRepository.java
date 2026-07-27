package com.reagent.persist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface EventRepository extends JpaRepository<EventEntity, Long> {

    /**
     * 补播游标查询:某任务里 id 大于游标的事件,按【自增 id】升序(= 事件发生序)。
     * Stage4 重连续播的核心查询;配合 (task_id, id) 复合索引走范围扫描。
     */
    List<EventEntity> findByTaskIdAndIdGreaterThanOrderByIdAsc(String taskId, Long id);

    @Query(value = """
            SELECT distinct_epochs.epoch_value
            FROM (
                SELECT starts.epoch_value, MIN(starts.event_id) AS first_event_id
                FROM (
                    SELECT id AS event_id,
                           CASE
                               WHEN JSON_VALID(data) = 0
                               THEN '__INVALID_LEASE_EPOCH__'
                               WHEN JSON_TYPE(JSON_EXTRACT(data, '$.leaseEpoch')) = 'INTEGER'
                               THEN JSON_UNQUOTE(JSON_EXTRACT(data, '$.leaseEpoch'))
                               ELSE '__INVALID_LEASE_EPOCH__'
                           END AS epoch_value
                    FROM event
                    WHERE task_id = :taskId AND type = :type
                ) starts
                GROUP BY starts.epoch_value
            ) distinct_epochs
            ORDER BY distinct_epochs.first_event_id
            """, nativeQuery = true)
    List<String> findOrderedDistinctEpochValues(
            @Param("taskId") String taskId,
            @Param("type") String type,
            Pageable pageable);
}
