package com.reagent.persist;

/** No persisted task exists for the requested ID. */
public class TaskNotFoundException extends IllegalArgumentException {

    public TaskNotFoundException(String taskId) {
        super("Task not found: " + taskId);
    }
}
