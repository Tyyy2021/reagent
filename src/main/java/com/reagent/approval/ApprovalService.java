package com.reagent.approval;

import com.reagent.core.AgentRunner;
import com.reagent.core.FaultContext;
import com.reagent.core.FaultInjector;
import com.reagent.core.FaultPoint;
import com.reagent.core.WorkerIdentity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ApprovalService {

    private final ApprovalDecisionTransaction transaction;
    private final AgentRunner runner;
    private final FaultInjector faultInjector;
    private final WorkerIdentity workerIdentity;

    public ApprovalService(
            ApprovalDecisionTransaction transaction,
            AgentRunner runner,
            FaultInjector faultInjector,
            WorkerIdentity workerIdentity
    ) {
        this.transaction = transaction;
        this.runner = runner;
        this.faultInjector = faultInjector;
        this.workerIdentity = workerIdentity;
    }

    public List<ApprovalView> list(String taskId) {
        return transaction.list(taskId);
    }

    public ApprovalView decide(
            String taskId,
            String toolCallId,
            ApprovalDecisionRequest request
    ) {
        ApprovalDecisionTransaction.DecisionOutcome outcome =
                transaction.decide(taskId, toolCallId, request);
        if (outcome.shouldResume()) {
            ApprovalView view = outcome.view();
            faultInjector.hit(
                    FaultPoint.AFTER_APPROVAL_DECIDED_BEFORE_RESUME,
                    new FaultContext(
                            taskId,
                            workerIdentity.id(),
                            outcome.leaseEpoch(),
                            Optional.of(toolCallId),
                            Optional.of(view.assistantMessageSeq())));
            runner.resumeAsync(taskId);
        }
        return outcome.view();
    }

    public boolean cancelWaiting(String taskId) {
        return transaction.cancelWaiting(taskId);
    }
}
