package com.reagent.approval;

import jakarta.persistence.LockModeType;
import com.reagent.persist.ToolCallEntity;
import com.reagent.persist.ToolCallStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository
        extends JpaRepository<ApprovalRequestEntity, String> {

    List<ApprovalRequestEntity> findByTaskIdOrderByAssistantMessageSeqAscToolCallIdAsc(
            String taskId);

    Optional<ApprovalRequestEntity> findByToolCallIdAndTaskId(
            String toolCallId, String taskId);

    long countByTaskIdAndStatus(String taskId, ApprovalStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from ApprovalRequestEntity a
             where a.toolCallId = :toolCallId and a.taskId = :taskId
            """)
    Optional<ApprovalRequestEntity> findForUpdate(
            @Param("taskId") String taskId,
            @Param("toolCallId") String toolCallId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from ApprovalRequestEntity a
             where a.taskId = :taskId
             order by a.assistantMessageSeq asc, a.toolCallId asc
            """)
    List<ApprovalRequestEntity> findAllForUpdate(
            @Param("taskId") String taskId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from ToolCallEntity t
             where t.id = :toolCallId and t.taskId = :taskId
            """)
    Optional<ToolCallEntity> findToolCallForUpdate(
            @Param("taskId") String taskId,
            @Param("toolCallId") String toolCallId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update ToolCallEntity t
               set t.status = :status,
                   t.result = :result,
                   t.completedAt = :completedAt
             where t.id = :toolCallId
               and t.taskId = :taskId
               and t.status = com.reagent.persist.ToolCallStatus.PENDING
            """)
    int completePendingToolCall(
            @Param("taskId") String taskId,
            @Param("toolCallId") String toolCallId,
            @Param("status") ToolCallStatus status,
            @Param("result") String result,
            @Param("completedAt") Instant completedAt);
}
