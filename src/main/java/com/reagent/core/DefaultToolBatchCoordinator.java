package com.reagent.core;

import com.reagent.persist.StateStore;
import com.reagent.persist.ToolCallStatus;
import com.reagent.profile.TaskToolCatalog;
import com.reagent.profile.ToolSnapshot;
import com.reagent.sandbox.RunJournal;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolExecutor;
import com.reagent.tool.ToolExecutionOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Current runtime tool-batch semantics behind the approval-ready coordinator seam. */
@Component
public class DefaultToolBatchCoordinator implements ToolBatchCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolBatchCoordinator.class);

    private final StateStore stateStore;
    private final ToolExecutor executor;
    private final StreamTransport bus;
    private final TaskControl taskControl;
    private final FaultInjector faultInjector;

    public DefaultToolBatchCoordinator(
            StateStore stateStore,
            ToolExecutor executor,
            StreamTransport bus,
            TaskControl taskControl,
            FaultInjector faultInjector
    ) {
        this.stateStore = stateStore;
        this.executor = executor;
        this.bus = bus;
        this.taskControl = taskControl;
        this.faultInjector = faultInjector;
    }

    @Override
    public BatchDisposition process(
            TaskRunToken token,
            ToolContext toolContext,
            Context context,
            TaskToolCatalog catalog,
            List<ToolCall> calls
    ) {
        if (!toolContext.runToken().filter(token::equals).isPresent()) {
            throw new IllegalArgumentException("ToolContext must carry the same TaskRunToken as the batch");
        }
        if (stateStore.prepareApprovalBarrier(token, catalog, calls)) {
            bus.publish(
                    token,
                    TaskEvent.Type.APPROVAL_REQUIRED,
                    Map.of("status", "WAITING_APPROVAL"));
            return BatchDisposition.WAITING_APPROVAL;
        }

        List<ToolCall> toRun = new ArrayList<>();
        List<ToolCall> actionable = new ArrayList<>();
        Map<String, String> inDoubt = new LinkedHashMap<>();
        Map<String, String> reconciled = new LinkedHashMap<>();
        for (ToolCall call : calls) {
            ToolCallStatus status = stateStore.statusOf(token, call.id());
            switch (status) {
                case DONE, IN_DOUBT, REJECTED ->
                        log.info("账本已是终态 {},跳过: {}", status, observableToolName(catalog, call));
                case IN_PROGRESS -> {
                    actionable.add(call);
                    if (canSafelyReplay(catalog, call)) {
                        log.info("in-doubt 但工具可安全重放,重跑: {}",
                                observableToolName(catalog, call));
                        toRun.add(call);
                    } else {
                        Optional<String> journaled = RunJournal.completion(toolContext.workspaceDir(), call.id());
                        if (journaled.isPresent()) {
                            log.info("in-doubt 但 journal 有完成记录(exit={}),对账标 DONE、不重跑: {}",
                                    journaled.get(), observableToolName(catalog, call));
                            reconciled.put(call.id(), reconciledMessage(call, journaled.get()));
                        } else {
                            log.warn("in-doubt 且无 journal 完成记录,未自动重试,上报存疑: {}",
                                    observableToolName(catalog, call));
                            inDoubt.put(call.id(), inDoubtMessage(call));
                        }
                    }
                }
                default -> {
                    actionable.add(call);
                    toRun.add(call);
                }
            }
        }

        Map<String, ToolExecutionOutcome> results = new HashMap<>();
        if (!toRun.isEmpty()) {
            for (ToolCall call : toRun) {
                stateStore.markInProgress(token, call);
                faultInjector.hit(
                        FaultPoint.AFTER_TOOL_MARKED_IN_PROGRESS,
                        faultContext(token, call));
            }
            for (ToolCall call : actionable) {
                bus.publish(token, TaskEvent.Type.TOOL_CALL, Map.of(
                        "id", call.id(), "name", observableToolName(catalog, call)));
            }
            log.info("并发执行本轮 {} 个工具调用", toRun.size());
            results.putAll(executor.executeConcurrently(catalog, toRun, toolContext));
        } else {
            for (ToolCall call : actionable) {
                bus.publish(token, TaskEvent.Type.TOOL_CALL, Map.of(
                        "id", call.id(), "name", observableToolName(catalog, call)));
            }
        }

        boolean forced = taskControl.signalOf(token.taskId()) == TaskControl.Signal.CANCEL
                && taskControl.isForced(token.taskId());
        if (forced) {
            Thread.interrupted();
        }

        Set<String> ran = new HashSet<>();
        for (ToolCall call : toRun) {
            ran.add(call.id());
        }
        boolean recoveryRequired = false;
        for (ToolCall call : calls) {
            if (ran.contains(call.id())) {
                if (forced && !canSafelyReplay(catalog, call)) {
                    log.warn("force-cancel 中途打断 SIDE_EFFECTFUL 工具,留 IN_PROGRESS(in-doubt)、不记 DONE: {}",
                            observableToolName(catalog, call));
                    bus.publish(token, TaskEvent.Type.TOOL_RESULT, Map.of(
                            "id", call.id(), "name", observableToolName(catalog, call),
                            "outcome", "IN_DOUBT", "inDoubt", true));
                } else {
                    ToolExecutionOutcome outcome = results.get(call.id());
                    if (outcome == null) {
                        throw new IllegalStateException(
                                "Tool execution produced no outcome for " + call.id());
                    }
                    if (outcome.kind()
                            == ToolExecutionOutcome.Kind.REMOTE_OUTCOME_UNKNOWN) {
                        recoveryRequired = true;
                        bus.publish(token, TaskEvent.Type.TOOL_RESULT, Map.of(
                                "id", call.id(),
                                "name", observableToolName(catalog, call),
                                "outcome", "REMOTE_OUTCOME_UNKNOWN",
                                "recoveryRequired", true));
                        continue;
                    }
                    String result = outcome.content();
                    stateStore.recordToolResult(token, call, result);
                    faultInjector.hit(
                            FaultPoint.AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER,
                            faultContext(token, call));
                    context.addToolResult(call.id(), result);
                    bus.publish(token, TaskEvent.Type.TOOL_RESULT,
                            Map.of(
                                    "id", call.id(), "name", observableToolName(catalog, call),
                                    "outcome", "DEFINITIVE"));
                }
            } else if (reconciled.containsKey(call.id())) {
                String message = reconciled.get(call.id());
                stateStore.recordToolResult(token, call, message);
                faultInjector.hit(
                        FaultPoint.AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER,
                        faultContext(token, call));
                context.addToolResult(call.id(), message);
                bus.publish(token, TaskEvent.Type.TOOL_RESULT,
                        Map.of(
                                "id", call.id(), "name", observableToolName(catalog, call),
                                "outcome", "DEFINITIVE", "reconciled", true));
            } else if (inDoubt.containsKey(call.id())) {
                String message = inDoubt.get(call.id());
                stateStore.markInDoubt(token, call, message);
                faultInjector.hit(
                        FaultPoint.AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER,
                        faultContext(token, call));
                context.addToolResult(call.id(), message);
                bus.publish(token, TaskEvent.Type.TOOL_RESULT,
                        Map.of(
                                "id", call.id(), "name", observableToolName(catalog, call),
                                "outcome", "IN_DOUBT", "inDoubt", true));
            }
        }
        return recoveryRequired
                ? BatchDisposition.RECOVERY_REQUIRED
                : BatchDisposition.EXECUTED;
    }

    private static FaultContext faultContext(TaskRunToken token, ToolCall call) {
        return new FaultContext(
                token.taskId(), token.workerId(), token.leaseEpoch(),
                Optional.of(call.id()), Optional.empty());
    }

    private static boolean canSafelyReplay(TaskToolCatalog catalog, ToolCall call) {
        ToolSnapshot tool = catalog.snapshot(call.name());
        if (tool == null) {
            return false;
        }
        IdempotencyClass idempotency = tool.idempotencyClass();
        return idempotency == IdempotencyClass.READ_ONLY || idempotency == IdempotencyClass.IDEMPOTENT;
    }

    private static String observableToolName(
            TaskToolCatalog catalog, ToolCall call) {
        ToolSnapshot snapshot = catalog.snapshot(call.name());
        return snapshot == null ? "unknown" : snapshot.name();
    }

    private static String reconciledMessage(ToolCall call, String exitCode) {
        return "ℹ️ 工具 " + call.name() + " 在上次执行中已【完成】(退出码 " + exitCode + "),"
                + "但进程在记账前崩溃;系统据沙箱完成日志(journal)对账确认,未重复执行。"
                + "(注:崩溃前的命令输出未留存,如需可用只读方式查看当前状态。)";
    }

    private static String inDoubtMessage(ToolCall call) {
        return "⚠️ 工具 " + call.name() + " 在上次执行中已开始、但进程崩溃前未确认完成,其副作用是否已生效【未知】。"
                + "为避免重复副作用,系统未自动重试。如有需要,请先用只读方式核对当前状态,再决定是否重新执行。";
    }
}
