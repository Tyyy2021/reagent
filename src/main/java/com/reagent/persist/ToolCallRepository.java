package com.reagent.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ToolCallRepository extends JpaRepository<ToolCallEntity, String> {

    Optional<ToolCallEntity> findByIdAndTaskId(String id, String taskId);

    boolean existsByIdAndTaskIdNot(String id, String taskId);
}
