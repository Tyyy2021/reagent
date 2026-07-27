package com.reagent.approval;

import com.reagent.core.AgentRunner;
import com.reagent.core.FaultContext;
import com.reagent.core.FaultInjector;
import com.reagent.core.FaultPoint;
import com.reagent.core.WorkerIdentity;
import com.reagent.obs.Trace;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ApprovalService {

    private final ApprovalDecisionTransaction transaction;
    private final AgentRunner runner;
    private final FaultInjector faultInjector;
    private final WorkerIdentity workerIdentity;
    private final Tracer tracer;

    @Autowired
    public ApprovalService(
            ApprovalDecisionTransaction transaction,
            AgentRunner runner,
            FaultInjector faultInjector,
            WorkerIdentity workerIdentity,
            Tracer tracer
    ) {
        this.transaction = transaction;
        this.runner = runner;
        this.faultInjector = faultInjector;
        this.workerIdentity = workerIdentity;
        this.tracer = tracer;
    }

    public ApprovalService(
            ApprovalDecisionTransaction transaction,
            AgentRunner runner,
            FaultInjector faultInjector,
            WorkerIdentity workerIdentity
    ) {
        this(transaction, runner, faultInjector, workerIdentity,
                OpenTelemetry.noop().getTracer(Trace.INSTRUMENTATION_NAME));
    }

    public List<ApprovalView> list(String taskId) {
        return transaction.list(taskId);
    }

    public ApprovalView decide(
            String taskId,
            String toolCallId,
            ApprovalDecisionRequest request
    ) {
        Span span = tracer.spanBuilder("approval.decision")
                .setParent(Trace.logicalRootContext(taskId))
                .setAttribute(Trace.TASK_ID, taskId)
                .setAttribute(Trace.APPROVAL_DECISION, request.decision().name())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
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
        } finally {
            span.end();
        }
    }

    public boolean cancelWaiting(String taskId) {
        return transaction.cancelWaiting(taskId);
    }
}
