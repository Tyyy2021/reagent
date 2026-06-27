package com.reagent.persist;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<TaskEntity, String> {

    /** 崩溃恢复用:启动时找出所有"卡在进行中"的任务 */
    List<TaskEntity> findByStatus(TaskStatus status);
}
