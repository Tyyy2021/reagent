package com.reagent.core;

import com.reagent.llm.LlmClient;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.ToolCallStatus;
import com.reagent.sandbox.RunJournal;
import com.reagent.sandbox.WorkspaceManager;
import com.reagent.stream.TaskEvent;
import com.reagent.stream.TaskEventBus;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolExecutor;
import com.reagent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** 停止条件:最多走多少步,防止 agent 死循环烧钱 */
    private static final int MAX_STEPS = 15;

    private static final String SYSTEM_PROMPT = """
            你是一个能够调用工具来完成任务的智能体(agent)。
            根据用户给出的目标,自主判断需要哪些信息,调用合适的工具一步步推进。
            每次只需决定下一步:要么调用一个工具,要么在信息足够时直接给出最终回答。
            当你认为任务已经完成,用简洁的自然语言给出最终结论,不要再调用工具。
            """;

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final StateStore stateStore;
    private final ShutdownState shutdownState;
    private final WorkspaceManager workspaceManager;
    private final InFlightTasks inFlight;
    private final TaskEventBus bus;

    public AgentRunner(LlmClient llm, ToolRegistry registry, ToolExecutor executor,
                       StateStore stateStore, ShutdownState shutdownState,
                       WorkspaceManager workspaceManager, InFlightTasks inFlight,
                       TaskEventBus bus) {
        this.llm = llm;
        this.registry = registry;
        this.executor = executor;
        this.stateStore = stateStore;
        this.shutdownState = shutdownState;
        this.workspaceManager = workspaceManager;
        this.inFlight = inFlight;
        this.bus = bus;
    }

    /** 提交一个新任务:落库后从头跑(同步,阻塞到跑完)。M4:仅 `POST /api/tasks?sync=true` 与单测走这条。 */
    public RunResult run(String goal) {
        log.info("====== 新任务:{} ======", goal);
        TaskEntity task = stateStore.createTask(goal, SYSTEM_PROMPT);
        String answer = drive(task);
        return new RunResult(task.getId(), answer);
    }

    /**
     * M4:异步提交。落库后把驱动丢到一根虚拟线程上跑,立即返回 taskId;过程经 {@link TaskEventBus}
     * 以 SSE 流式推给订阅者。任务<b>不绑 HTTP 连接</b>——客户端断开/重连都不影响它(本就持久可恢复)。
     */
    public String submit(String goal) {
        log.info("====== 新任务(异步):{} ======", goal);
        TaskEntity task = stateStore.createTask(goal, SYSTEM_PROMPT);
        String taskId = task.getId();
        Thread.ofVirtual().name("agent-" + taskId).start(() -> {
            try {
                drive(task);
            } catch (RuntimeException ex) {
                // drive 内部已分流(shutdown 保 RUNNING / 真错判 FAILED);此处兜底,防虚拟线程静默吞异常
                log.error("异步任务 {} 驱动异常", taskId, ex);
            }
        });
        return taskId;
    }

    /** 恢复一个半截任务:从库里重建上下文接着跑(崩溃恢复 / 手动续跑都走这里)。 */
    public String resume(String taskId) {
        log.info("====== 恢复任务:{} ======", taskId);
        return drive(stateStore.getTask(taskId));
    }

    /**
     * 真正的循环。对"全新任务"和"半截任务"是同一套代码:
     * 每轮先把库里(可能)欠着的工具结果补齐,再问模型下一步。
     */
    private String drive(TaskEntity task) {
        String taskId = task.getId();
        // 单机内"同一任务不并发驱动"护栏:挡住"自动恢复 + 手动 resume 撞车"——否则两个 drive
        // 会撞 message.seq、还会把同一工具跑两遍(账本 DONE 检查与执行之间不是原子的)。
        // 跨进程的同源竞态由 (task_id, seq) 唯一约束 fail-fast;彻底解决留给 M7 的 DB 租约。
        if (!inFlight.tryBegin(taskId)) {
            log.warn("任务 {} 已在本进程内执行中,跳过本次重复驱动。", taskId);
            return "任务已在执行中,本次重复驱动已跳过。";
        }
        try {
            return driveLoop(taskId);
        } finally {
            inFlight.end(taskId);
        }
    }

    /** 实际的 ReAct 驱动循环(被 {@link #drive} 包上"同一任务单飞"护栏后调用)。 */
    private String driveLoop(String taskId) {
        Context ctx = stateStore.loadContext(taskId);
        // 本任务的工具执行上下文:taskId + 独立工作目录(沙箱 cwd / 挂载点),一次解析、全程复用
        ToolContext toolCtx = new ToolContext(taskId, workspaceManager.workspaceFor(taskId));

        try {
            bus.publish(taskId, TaskEvent.Type.TASK_STARTED, Map.of());
            for (int step = 1; step <= MAX_STEPS; step++) {
                bus.publish(taskId, TaskEvent.Type.STEP, Map.of("step", step));
                // 1. 崩溃残留 / 上一轮未完的工具调用,先补跑
                List<ToolCall> pending = ctx.pendingToolCalls();
                if (!pending.isEmpty()) {
                    log.info("--- 第 {} 步:补跑 {} 个未完成的工具调用 ---", step, pending.size());
                    executeTools(toolCtx, ctx, pending);
                    continue;
                }

                // 2. 问大脑:下一步干什么
                log.info("--- 第 {} 步:询问模型 ---", step);
                Decision decision = llm.chat(ctx, registry.toOpenAiSpec());

                // 3. 大脑认为任务完成了
                if (decision.isFinal()) {
                    stateStore.appendAssistant(taskId, decision.getAssistantMessage());
                    ctx.addAssistant(decision.getAssistantMessage());
                    stateStore.completeTask(taskId, decision.getAnswer());
                    bus.publish(taskId, TaskEvent.Type.COMPLETED, Map.of("result", String.valueOf(decision.getAnswer())));
                    log.info("====== 任务完成 ======\n{}", decision.getAnswer());
                    return decision.getAnswer();
                }

                // 4. 大脑要调工具:先落库 assistant(顺带登记 PENDING 账本),再执行
                stateStore.appendAssistant(taskId, decision.getAssistantMessage());
                ctx.addAssistant(decision.getAssistantMessage());
                for (ToolCall call : decision.getToolCalls()) {
                    bus.publish(taskId, TaskEvent.Type.TOOL_CALL,
                            Map.of("id", call.id(), "name", call.name(), "arguments", call.arguments()));
                }
                executeTools(toolCtx, ctx, decision.getToolCalls());
            }

            log.warn("达到最大步数 {},任务未在限定步数内完成。", MAX_STEPS);
            String msg = "达到最大步数(" + MAX_STEPS + "),任务未能完成。";
            stateStore.failTask(taskId, msg);
            bus.publish(taskId, TaskEvent.Type.FAILED, Map.of("error", msg));
            return msg;

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
            log.error("任务 {} 执行异常,标记 FAILED", taskId, ex);
            stateStore.failTask(taskId, "执行异常: " + ex.getMessage());
            bus.publish(taskId, TaskEvent.Type.FAILED, Map.of("error", String.valueOf(ex.getMessage())));
            return "任务执行失败:" + ex.getMessage();
        }
    }

    /**
     * 执行本轮所有工具,并为【每个】tool_call_id 回一条结果(协议硬性要求)。
     *
     * <p>exactly-once 的核心落点 —— 三步,中间夹一道"在途"持久化栅栏:</p>
     * <ol>
     *  <li><b>决策表分流</b>:查账本状态决定每个 call 怎么处理——{@code DONE/IN_DOUBT}=终态跳过;
     *      {@code IN_PROGRESS}=上一世崩在 in-doubt 窗口(只读/幂等工具安全重跑、有副作用工具不盲目重试而上报存疑);
     *      {@code PENDING}=从没开跑、安全执行。</li>
     *  <li><b>栅栏 + 并发执行</b>:对要跑的工具先逐个 {@link StateStore#markInProgress} 把"在途" <b>commit</b>
     *      (在副作用之前!这样崩溃后能区分"一定没做"与"可能做了"),再交 {@link ToolExecutor#executeConcurrently} 并发跑。</li>
     *  <li><b>串行落库</b>:按【原始顺序】把结果写账本(DONE)/存疑(IN_DOUBT)+ 写回上下文。</li>
     * </ol>
     *
     * <p>第 3 步串行的原因不变:message.seq 用 countByTaskId 生成、Context 是普通 ArrayList,都非线程安全;
     * 落库很轻,真正耗时的执行已在第 2 步并发掉,串行最稳、也避开账本/seq 的并发写竞争。</p>
     */
    private void executeTools(ToolContext toolCtx, Context ctx, List<ToolCall> calls) {
        String taskId = toolCtx.taskId();

        // 1. 恢复决策表分流
        List<ToolCall> toRun = new ArrayList<>();
        Map<String, String> inDoubt = new LinkedHashMap<>();      // id -> 存疑提示(待落库 IN_DOUBT)
        Map<String, String> reconciled = new LinkedHashMap<>();   // id -> 对账完成提示(L3:journal 有完成记录 -> 落库 DONE)
        for (ToolCall call : calls) {
            ToolCallStatus status = stateStore.statusOf(call.id());
            switch (status) {
                case DONE, IN_DOUBT -> {
                    // 终态:正常路径到不了这;恢复时这类已带 tool 消息、被 pendingToolCalls 滤掉。
                    // 结果已在持久历史 / 重建后的 ctx 里 → 不重跑、不重复落库。
                    log.info("账本已是终态 {},跳过: {}", status, call.name());
                }
                case IN_PROGRESS -> {
                    // 上一世开跑过没收尾 = in-doubt 窗口
                    if (canSafelyReplay(call)) {
                        log.info("in-doubt 但工具可安全重放,重跑: {}", call.name());
                        toRun.add(call);
                    } else {
                        // 非幂等工具崩在 in-doubt 窗口。L3:先查沙箱完成日志(journal)对账——
                        //   有完成记录 = 命令其实跑完了(只是没记账)-> 标 DONE、不重跑(把"保守上报"救回成"确定完成");
                        //   无完成记录 = 真崩在执行中途 -> 上报存疑(任意 shell 的理论下限,诚实接受)。
                        Optional<String> journaled = RunJournal.completion(toolCtx.workspaceDir(), call.id());
                        if (journaled.isPresent()) {
                            log.info("in-doubt 但 journal 有完成记录(exit={}),对账标 DONE、不重跑: {}",
                                    journaled.get(), call.name());
                            reconciled.put(call.id(), reconciledMessage(call, journaled.get()));
                        } else {
                            log.warn("in-doubt 且无 journal 完成记录,未自动重试,上报存疑: {}", call.name());
                            inDoubt.put(call.id(), inDoubtMessage(call));
                        }
                    }
                }
                default -> {
                    // PENDING / 未登记:从没开跑 → 副作用一定没发生 → 安全执行
                    toRun.add(call);
                }
            }
        }

        // 2. 栅栏 + 并发执行:先把"在途"持久化(必须在副作用之前 commit),再并发跑
        Map<String, String> results = new HashMap<>();
        if (!toRun.isEmpty()) {
            for (ToolCall call : toRun) {
                stateStore.markInProgress(taskId, call);
            }
            log.info("并发执行本轮 {} 个工具调用", toRun.size());
            results.putAll(executor.executeConcurrently(toRun, toolCtx));
        }

        // 3. 按【原始顺序】串行落库 + 写回上下文
        Set<String> ran = new HashSet<>();
        for (ToolCall c : toRun) {
            ran.add(c.id());
        }
        for (ToolCall call : calls) {
            if (ran.contains(call.id())) {
                String result = results.get(call.id());
                stateStore.recordToolResult(taskId, call, result);   // 账本 DONE + tool 消息
                ctx.addToolResult(call.id(), result);
                bus.publish(taskId, TaskEvent.Type.TOOL_RESULT,
                        Map.of("id", call.id(), "name", call.name(), "result", String.valueOf(result)));
            } else if (reconciled.containsKey(call.id())) {
                String msg = reconciled.get(call.id());
                stateStore.recordToolResult(taskId, call, msg);      // L3 对账:账本 DONE + tool 消息(不重放副作用)
                ctx.addToolResult(call.id(), msg);
                bus.publish(taskId, TaskEvent.Type.TOOL_RESULT,
                        Map.of("id", call.id(), "name", call.name(), "result", msg, "reconciled", true));
            } else if (inDoubt.containsKey(call.id())) {
                String msg = inDoubt.get(call.id());
                stateStore.markInDoubt(taskId, call, msg);            // 账本 IN_DOUBT + tool 消息
                ctx.addToolResult(call.id(), msg);
                bus.publish(taskId, TaskEvent.Type.TOOL_RESULT,
                        Map.of("id", call.id(), "name", call.name(), "result", msg, "inDoubt", true));
            }
            // else: 终态,跳过(结果已在历史 / ctx)
        }
    }

    /** 这次 in-doubt 的调用能否安全重放:只读 / 幂等工具可以;有副作用 / 未知工具一律不重放(fail-closed)。 */
    private boolean canSafelyReplay(ToolCall call) {
        Tool tool = registry.get(call.name());
        if (tool == null) {
            return false;
        }
        IdempotencyClass cls = tool.idempotency();
        return cls == IdempotencyClass.READ_ONLY || cls == IdempotencyClass.IDEMPOTENT;
    }

    /** L3 对账:journal 证明命令上次已执行完成,标 DONE 回给模型(绝不重放副作用)。 */
    private static String reconciledMessage(ToolCall call, String exitCode) {
        return "ℹ️ 工具 " + call.name() + " 在上次执行中已【完成】(退出码 " + exitCode + "),"
                + "但进程在记账前崩溃;系统据沙箱完成日志(journal)对账确认,未重复执行。"
                + "(注:崩溃前的命令输出未留存,如需可用只读方式查看当前状态。)";
    }

    /** 给模型看的"结果存疑"观察:崩在 in-doubt 窗口、未自动重试,交由模型核对 / 重发。 */
    private static String inDoubtMessage(ToolCall call) {
        return "⚠️ 工具 " + call.name() + " 在上次执行中已开始、但进程崩溃前未确认完成,其副作用是否已生效【未知】。"
                + "为避免重复副作用,系统未自动重试。如有需要,请先用只读方式核对当前状态,再决定是否重新执行。";
    }

    /** 提交任务的返回:任务 id(可据此查状态 / 手动恢复)+ 最终结果 */
    public record RunResult(String taskId, String result) {
    }
}
