package com.reagent.core;

import com.reagent.persist.TaskStatus;

public final class FencedExecutionException extends RuntimeException {

    private final TaskRunToken token;
    private final String actualOwner;
    private final long actualEpoch;
    private final TaskStatus actualStatus;

    public FencedExecutionException(TaskRunToken token, String actualOwner,
                                    long actualEpoch, TaskStatus actualStatus) {
        super("Task run fenced: task=" + token.taskId()
                + ", tokenOwner=" + token.workerId()
                + ", tokenEpoch=" + token.leaseEpoch()
                + ", actualOwner=" + actualOwner
                + ", actualEpoch=" + actualEpoch
                + ", actualStatus=" + actualStatus);
        this.token = token;
        this.actualOwner = actualOwner;
        this.actualEpoch = actualEpoch;
        this.actualStatus = actualStatus;
    }

    public TaskRunToken token() {
        return token;
    }

    public String actualOwner() {
        return actualOwner;
    }

    public long actualEpoch() {
        return actualEpoch;
    }

    public TaskStatus actualStatus() {
        return actualStatus;
    }
}
