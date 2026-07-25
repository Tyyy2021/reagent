package com.reagent.core;

import com.reagent.llm.LlmClient;
import com.reagent.obs.Trace;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskStatus;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.TaskToolCatalog;
import com.reagent.profile.ToolCatalogResolver;
import com.reagent.sandbox.WorkspaceStore;
import com.reagent.stream.TaskEvent;
import com.reagent.stream.StreamTransport;
import com.reagent.tool.ToolContext;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★ 整个项目的心脏:ReAct 主循环。
 *
 * 给个目标 -> 问大脑"下一步干啥" -> 大脑说调某工具 -> 执行 -> 把结果喂回大脑
 *   -> 再问 -> ...... 直到大脑说"完成了",或达到步数上限。
 *
 * M2 在 M1 的循环上做了两件加固:
 *  1. 每一步都通过 {@link StateStore} 落库(任务状态、消息、工具账本);
 *  2. 循环写成"可恢复"的形态——每轮开头先看有没有"欠着的工具结果"(崩溃残留),
 *     有就先补跑;这样无论从全新任务还是半截任务进来,跑法完全一致。
 *
 * 后续里程碑继续加固:工具沙箱 + 并发(M3)、每步 SSE 推送(M4)。
 */
@Service
public class AgentRunner {

    private static final String TASK_EXECUTION_FAILED =
            "task_execution_failed";

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** 停止条件:最多走多少步,防止 agent 死循环烧钱 */
    private static final int MAX_STEPS = 15;

    private final LlmClient llm;
    private final AgentProfileRegistry profileRegistry;
    private final ToolCatalogResolver catalogResolver;
    private final ToolBatchCoordinator toolBatchCoordinator;
    private final FaultInjector faultInjector;
    private final StateStore stateStore;
    private final ShutdownState shutdownState;
    private final WorkspaceStore workspaceStore;
    private final InFlightTasks inFlight;
    private final StreamTransport bus;
    private final TaskControl taskControl;
    private final Tracer tracer;
    private final WorkerIdentity workerIdentity;   // M7:本 worker 身份(span 属性 / 日志)
    private final int maxAttempts;                 // M7 Stage2:单任务自动恢复次数上限(止损)

    public AgentRunner(LlmClient llm, AgentProfileRegistry profileRegistry,
                       ToolCatalogResolver catalogResolver, ToolBatchCoordinator toolBatchCoordinator,
                       FaultInjector faultInjector,
                       StateStore stateStore, ShutdownState shutdownState,
                       WorkspaceStore workspaceStore, InFlightTasks inFlight,
                       StreamTransport bus, TaskControl taskControl, Tracer tracer,
                       WorkerIdentity workerIdentity,
                       @Value("${reagent.recovery.max-attempts:3}") int maxAttempts) {
        this.llm = llm;
        this.profileRegistry = profileRegistry;
        this.catalogResolver = catalogResolver;
        this.toolBatchCoordinator = toolBatchCoordinator;
        this.faultInjector = faultInjector;
        this.stateStore = stateStore;
        this.shutdownState = shutdownState;
        this.workspaceStore = workspaceStore;
        this.inFlight = inFlight;
        this.bus = bus;
        this.taskControl = taskControl;
        this.tracer = tracer;
        this.workerIdentity = workerIdentity;
        this.maxAttempts = maxAttempts;
    }

    /** 提交一个新任务:落库后从头跑(同步,阻塞到跑完)。M4:仅 `POST /api/tasks?sync=true` 与单测走这条。 */
    public RunResult run(String goal) {
        return run(goal, "coding");
    }

    public RunResult run(String goal, String profileId) {
        log.info("====== 新任务 ======");
        TaskProfileSnapshot snapshot = profileRegistry.snapshot(profileId);
        TaskToolCatalog catalog = catalogResolver.resolve(snapshot);
        TaskEntity task = stateStore.createTask(goal, snapshot);
        String answer = drive(task, false, catalog);
        return new RunResult(task.getId(), answer);
    }

    /**
     * M4:异步提交。落库后把驱动丢到一根虚拟线程上跑,立即返回 taskId;过程经 {@link TaskEventBus}
     * 以 SSE 流式推给订阅者。任务<b>不绑 HTTP 连接</b>——客户端断开/重连都不影响它(本就持久可恢复)。
     */
    public String submit(String goal) {
        return submit(goal, "coding");
    }

    public String submit(String goal, String profileId) {
        log.info("====== 新任务(异步) ======");
        TaskProfileSnapshot snapshot = profileRegistry.snapshot(profileId);
        TaskToolCatalog catalog = catalogResolver.resolve(snapshot);
        TaskEntity task = stateStore.createTask(goal, snapshot);
        String taskId = task.getId();
        Thread.ofVirtual().name("agent-" + taskId).start(() -> {
            try {
                drive(task, false, catalog);
            } catch (RuntimeException ex) {
                // drive 内部已分流(shutdown 保 RUNNING / 真错判 FAILED);此处兜底,防虚拟线程静默吞异常
                log.error("异步任务 {} 驱动异常", taskId);
            }
        });
        return taskId;
    }

    /** 恢复一个半截任务:从库里重建上下文接着跑(崩溃恢复 / 手动续跑都走这里)。 */
    public String resume(String taskId) {
        log.info("====== 恢复任务:{} ======", taskId);
        TaskEntity task = stateStore.getTask(taskId);
        if (task.getStatus() == TaskStatus.PAUSED) {
            stateStore.markRunning(taskId);   // 暂停的任务:先 PAUSED -> RUNNING 再续跑
        }
        return drive(task, false, null);
    }

    /**
     * ★ M7 Stage2:自动恢复 / 失败转移入口。与 {@link #resume}(用户手动)的区别:走 autoRecovery 路径 ——
     * 计恢复次数并止损。由 {@link FailoverService} 在虚拟线程上调用;claim 落败者会被 {@link #drive} 内部
     * 干净跳过(不计数、不驱动),所以多 worker 同时盯同一孤儿任务也安全。
     */
    public String recover(String taskId) {
        log.info("====== 接管 / 恢复任务:{} ======", taskId);
        TaskEntity task = stateStore.getTask(taskId);
        return drive(task, true, null);
    }

    /** M4:异步恢复(给 resume 端点用)——在虚拟线程上续跑、立即返回,过程经 SSE 流式推。 */
    public void resumeAsync(String taskId) {
        Thread.ofVirtual().name("agent-resume-" + taskId).start(() -> {
            try {
                resume(taskId);
            } catch (RuntimeException ex) {
                log.error("异步恢复任务 {} 异常", taskId);
            }
        });
    }

    /**
     * 真正的循环。对"全新任务"和"半截任务"是同一套代码:
     * 每轮先把库里(可能)欠着的工具结果补齐,再问模型下一步。
     */
    private String drive(TaskEntity task, boolean autoRecovery, TaskToolCatalog catalog) {
        String taskId = task.getId();
        // 单机内"同一任务不并发驱动"护栏:挡住"自动恢复 + 手动 resume 撞车"——否则两个 drive
        // 会撞 message.seq、还会把同一工具跑两遍(账本 DONE 检查与执行之间不是原子的)。
        if (!inFlight.tryBegin(taskId)) {
            log.warn("任务 {} 已在本进程内执行中,跳过本次重复驱动。", taskId);
            return "任务已在执行中,本次重复驱动已跳过。";
        }
        try {
            // M7:跨【进程】护栏 —— 原子 claim 抢租约,返回本次持有的完整 run token;empty=没抢到。
            // 替掉过去"凡 RUNNING 都是我崩的、全量抢恢复"的单机假设:别的 worker 正跑的任务(租约被心跳续着、
            // 未过期)在此 claim 失败被干净跳过。InFlightTasks 只管 JVM 内,跨进程同源竞态由这道 DB 租约收口。
            Optional<TaskRunToken> claimed = stateStore.claim(taskId);
            if (claimed.isEmpty()) {
                log.warn("任务 {} 被其它 worker 持有(claim 失败),跳过本次驱动。", taskId);
                return "任务已被其它 worker 持有,本次驱动跳过。";
            }
            TaskRunToken token = claimed.orElseThrow();
            try {
                // Recovery/resume resolves the frozen catalog only after this worker wins the lease.
                // Legacy NULL snapshots are materialized by the token-guarded StateStore overload.
                if (catalog == null) {
                    catalog = catalogResolver.resolve(stateStore.loadProfile(token));
                }
                // M7 Stage2:只有【自动恢复 / 失败转移】路径才计恢复次数并止损(手动 resume / 新任务不计)。
                // 放在 claim 之后:只有真抢到执行权的 worker 才 +1,落败的 worker 直接跳过、绝不误加计数。
                if (autoRecovery) {
                    int completedAttempts = stateStore.recoveryCount(token);
                    if (completedAttempts >= maxAttempts) {
                        log.warn("任务 {} 已自动恢复 {} 次仍未完成,达到上限 {},止损标记 FAILED。",
                                taskId, completedAttempts, maxAttempts);
                        stateStore.failTask(token, "超过最大自动恢复次数(" + maxAttempts + "),停止自动恢复以免反复烧钱。");
                        return "超过最大自动恢复次数,已止损标记 FAILED。";
                    }
                    int attempt = stateStore.incrementRecoveryCount(token);
                    log.info("自动恢复 / 接管任务 {}(第 {}/{} 次)", taskId, attempt, maxAttempts);
                }
            } catch (FencedExecutionException ex) {
                log.warn("任务 {} 的恢复前置步骤已被 fence,停止旧驱动且不写 FAILED", taskId);
                return "本任务已被其它 worker 接管(fence),本 worker 停止驱动。";
            }
            taskControl.begin(token);   // 登记驱动线程 + 打断信号槽 + 本次运行 token(M4 / M7 Stage3)
            // M6:任务 root span —— 用 taskId 派生 traceId,新任务与每次恢复都落在【同一条 trace】下(跨崩溃可视);
            // root 不继承 HTTP 请求的 trace(任务与连接解耦、活得比连接长),与 M4 解耦一脉相承。
            Span taskSpan = tracer.spanBuilder("agent.task")
                    .setParent(Trace.logicalRootContext(taskId))
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute(Trace.TASK_ID, taskId)
                    .setAttribute(Trace.RECOVERY_COUNT, (long) task.getRecoveryCount())
                    .setAttribute(Trace.WORKER_ID, workerIdentity.id())   // M7:标出本次由哪个 worker 驱动(失败转移后可见接管)
                    .startSpan();
            try (Scope ignored = taskSpan.makeCurrent()) {
                return driveLoop(token, catalog);
            } catch (RuntimeException ex) {
                taskSpan.setStatus(StatusCode.ERROR, "agent task failed");
                throw ex;
            } finally {
                try {
                    taskSpan.setAttribute(Trace.TASK_STATUS, stateStore.getTask(taskId).getStatus().name());
                } catch (RuntimeException ignore) {
                    // 读终态失败不影响收尾
                }
                taskSpan.end();
                taskControl.end(taskId);
            }
        } finally {
            inFlight.end(taskId);
        }
    }

    /** 实际的 ReAct 驱动循环(被 {@link #drive} 包上"同一任务单飞"护栏后调用)。 */
    private String driveLoop(TaskRunToken token, TaskToolCatalog catalog) {
        String taskId = token.taskId();
        Context ctx = stateStore.loadContext(taskId);
        // 本任务的工具执行上下文:taskId + 独立工作目录(沙箱 cwd / 挂载点),一次解析、全程复用
        ToolContext toolCtx = new ToolContext(token, workspaceStore.checkout(taskId));

        try {
            bus.publish(token, TaskEvent.Type.TASK_STARTED, Map.of());
            for (int step = 1; step <= MAX_STEPS; step++) {
                // M6:每步一个 span(parent = 当前 agent.task span);span scope 内开的 llm/tool span 自动挂其下
                Span stepSpan = tracer.spanBuilder("agent.step")
                        .setAttribute(Trace.STEP_NUMBER, (long) step)
                        .startSpan();
                try (Scope ignored = stepSpan.makeCurrent()) {
                    // 安全点①(步与步之间):被取消/暂停则在此干净停止,不打断任何 in-flight 工具
                    String interrupted = checkInterrupt(token);
                    if (interrupted != null) return interrupted;

                    bus.publish(token, TaskEvent.Type.STEP, Map.of("step", step));
                    // 1. 崩溃残留 / 上一轮未完的工具调用,先补跑
                    List<ToolCall> pending = ctx.pendingToolCalls();
                    if (!pending.isEmpty()) {
                        stepSpan.setAttribute(Trace.STEP_PENDING, true);
                        log.info("--- 第 {} 步:补跑 {} 个未完成的工具调用 ---", step, pending.size());
                        BatchDisposition disposition = toolBatchCoordinator.process(
                                token, toolCtx, ctx, catalog, pending);
                        if (disposition == BatchDisposition.WAITING_APPROVAL) {
                            return "任务正在等待审批。";
                        }
                        workspaceStore.commit(taskId);   // M7 C:工具可能改了工作区 → 同步给其它 worker(shared-fs no-op)
                        if (disposition == BatchDisposition.RECOVERY_REQUIRED) {
                            return "任务需要恢复对账。";
                        }
                        continue;
                    }

                    // 2. 问大脑:下一步干什么
                    log.info("--- 第 {} 步:询问模型 ---", step);
                    Decision decision = llm.chatStream(ctx, catalog.toOpenAiSpec(),
                            tokenText -> bus.publish(token, TaskEvent.Type.TOKEN, Map.of("text", tokenText)));

                    // 安全点②(LLM 调用可能耗时,期间若被取消/暂停,在启动工具【之前】停)
                    interrupted = checkInterrupt(token);
                    if (interrupted != null) return interrupted;

                    // 3. 大脑认为任务完成了
                    if (decision.isFinal()) {
                        stateStore.appendAssistant(token, decision.getAssistantMessage());
                        ctx.addAssistant(decision.getAssistantMessage());
                        stateStore.completeTask(token, decision.getAnswer());
                        bus.publish(token, TaskEvent.Type.COMPLETED, Map.of("result", String.valueOf(decision.getAnswer())));
                        log.info("====== 任务完成 ======\n{}", decision.getAnswer());
                        return decision.getAnswer();
                    }

                    // 4. 大脑要调工具:先落库 assistant(顺带登记 PENDING 账本),再执行
                    int assistantSequence = stateStore.appendAssistant(token, decision.getAssistantMessage());
                    faultInjector.hit(
                            FaultPoint.AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL,
                            new FaultContext(
                                    token.taskId(), token.workerId(), token.leaseEpoch(),
                                    Optional.empty(), Optional.of(assistantSequence)));
                    ctx.addAssistant(decision.getAssistantMessage());
                    BatchDisposition disposition = toolBatchCoordinator.process(
                            token, toolCtx, ctx, catalog, decision.getToolCalls());
                    if (disposition == BatchDisposition.WAITING_APPROVAL) {
                        return "任务正在等待审批。";
                    }
                    workspaceStore.commit(taskId);   // M7 C:工具可能改了工作区 → 同步给其它 worker(shared-fs no-op)
                    if (disposition == BatchDisposition.RECOVERY_REQUIRED) {
                        return "任务需要恢复对账。";
                    }
                } finally {
                    stepSpan.end();
                }
            }

            log.warn("达到最大步数 {},任务未在限定步数内完成。", MAX_STEPS);
            String msg = "达到最大步数(" + MAX_STEPS + "),任务未能完成。";
            stateStore.failTask(token, msg);
            bus.publish(token, TaskEvent.Type.FAILED, Map.of("error", msg));
            return msg;

        } catch (InjectedWorkerCrashException ex) {
            log.warn("任务 {} 在受控故障点 {} 模拟进程消失,保持 RUNNING 且不写 FAILED。",
                    taskId, ex.point());
            throw ex;
        } catch (FencedExecutionException ex) {
            log.warn("任务 {} 的运行 token 已被 fence,停止旧驱动且不写 FAILED", taskId);
            return "本任务已被其它 worker 接管(fence),本 worker 停止驱动。";
        } catch (RuntimeException ex) {
            // 关键区分:这个异常是"进程要关了"造成的,还是任务本身真出错了?
            //  - 进程关闭(IDE 停止 / kill / 滚动更新)打断了线程 -> 不是失败,
            //    任务保持 RUNNING,重启后由崩溃恢复接着跑(这正是可恢复运行时该有的语义)。
            //  - 真·进程被硬杀(SIGKILL),根本到不了这里,任务自然停在 RUNNING,同样可恢复。
            //  - 只有应用层真错误(如 LLM 报 400)才判 FAILED,且不自动重试以免烧钱。
            if (shutdownState.isShuttingDown()) {
                log.warn("进程正在关闭,任务 {} 保持 RUNNING,重启后将自动恢复。", taskId);
                return "进程关闭,任务将于重启后恢复。";
            }
            log.error("任务 {} 执行异常,标记 FAILED", taskId);
            try {
                stateStore.failTask(token, "执行异常: " + ex.getMessage());
                bus.publish(
                        token,
                        TaskEvent.Type.FAILED,
                        Map.of("error", TASK_EXECUTION_FAILED));
            } catch (FencedExecutionException fenced) {
                log.warn("任务 {} 写 FAILED 前已被接管,旧 worker 停止且不发布 FAILED",
                        taskId);
                return "本任务已被其它 worker 接管(fence),本 worker 停止驱动。";
            }
            return "任务执行失败:" + ex.getMessage();
        }
    }

    /**
     * 安全点检查打断信号(M4 Stage3a):
     *  - CANCEL -> 落库 CANCELLED + publish,返回收尾消息;
     *  - PAUSE  -> 落库 PAUSED + publish,返回收尾消息(留下可 resume 的干净状态);
     *  - NONE   -> 返回 null(继续跑)。
     *
     * <p>只在"无 in-flight 工具"的安全点被调用(循环顶 / LLM 调用后),故优雅 drain 自然成立:
     * 取消/暂停若在工具执行期间到达,当前批次先跑完,下一轮循环顶才在此停。</p>
     */
    private String checkInterrupt(TaskRunToken token) {
        String taskId = token.taskId();
        TaskControl.Signal sig = taskControl.signalOf(taskId);
        if (sig == TaskControl.Signal.FENCED) {
            // M7 Stage3:租约已被其它 worker 接管 —— 本 worker 干净停手,且【绝不改任务状态】(状态归接管者)。
            log.warn("任务 {} 被 fence(租约已易主),在安全点停止驱动、不动状态(交接管者续跑)。", taskId);
            return "本任务已被其它 worker 接管(fence),本 worker 停止驱动。";
        }
        // M7 Stage4:本地无信号时,看跨 worker 的 DB 控制信号(任意 worker 下达、当前 owner 在此消费)——控制面位置透明。
        if (sig == TaskControl.Signal.NONE) {
            String db = stateStore.readControlSignal(taskId);
            if ("CANCEL".equals(db)) {
                sig = TaskControl.Signal.CANCEL;
            } else if ("PAUSE".equals(db)) {
                sig = TaskControl.Signal.PAUSE;
            }
        }
        if (sig == TaskControl.Signal.CANCEL) {
            String msg = "任务已被用户取消。";
            stateStore.cancelTask(token, msg);
            bus.publish(token, TaskEvent.Type.CANCELLED, Map.of("status", "CANCELLED"));
            log.info("任务 {} 在安全点被取消,停止。", taskId);
            return msg;
        }
        if (sig == TaskControl.Signal.PAUSE) {
            String msg = "任务已暂停,可通过 resume 续跑。";
            stateStore.pauseTask(token);
            bus.publish(token, TaskEvent.Type.PAUSED, Map.of("status", "PAUSED"));
            log.info("任务 {} 在安全点被暂停。", taskId);
            return msg;
        }
        return null;
    }
    /** 提交任务的返回:任务 id(可据此查状态 / 手动恢复)+ 最终结果 */
    public record RunResult(String taskId, String result) {
    }
}
