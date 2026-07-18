package com.reagent.persist;

import com.reagent.core.FencedExecutionException;
import com.reagent.core.TaskRunToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;

@Component
public class TaskLeaseGuard {

    private final TaskRepository taskRepository;

    public TaskLeaseGuard(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TaskEntity lockOwned(TaskRunToken token, Set<TaskStatus> allowedStatuses) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(allowedStatuses, "allowedStatuses");
        TaskEntity task = taskRepository.findByIdForUpdate(token.taskId())
                .orElseThrow(() -> new FencedExecutionException(token, null, -1, null));
        if (!task.getId().equals(token.taskId())
                || !Objects.equals(task.getOwnerId(), token.workerId())
                || task.getLeaseEpoch() != token.leaseEpoch()
                || !allowedStatuses.contains(task.getStatus())) {
            throw new FencedExecutionException(
                    token, task.getOwnerId(), task.getLeaseEpoch(), task.getStatus());
        }
        return task;
    }
}
