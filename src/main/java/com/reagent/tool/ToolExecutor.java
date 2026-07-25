package com.reagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.ToolCall;
import com.reagent.core.FencedExecutionException;
import com.reagent.core.InjectedWorkerCrashException;
import com.reagent.mcp.RemoteOutcomeUnknownException;
import com.reagent.obs.Trace;
import com.reagent.profile.TaskToolCatalog;
import com.reagent.profile.ToolSnapshot;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行器。负责:找到工具 -> 解析参数 -> 执行 -> 把异常兜成可读结果。
 *
 * 关键:工具执行失败不能让整个 agent 崩,而是把错误信息当作"观察结果"
 * 喂回给模型,让模型自己决定怎么纠错(这是 agent 鲁棒性的来源)。
 *
 * M3:同一轮模型要求的多个 tool_call 在这里【并发】执行(JDK21 虚拟线程),
 *     每个工具各自【超时】。沙箱隔离(子进程硬杀 / Docker)是下一层,会替换掉
 *     这里进程内的 {@link Tool#execute};并发与超时框架先就位。
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ObjectMapper mapper;
    private final ToolProperties props;
    private final Tracer tracer;

    public ToolExecutor(ObjectMapper mapper, ToolProperties props, Tracer tracer) {
        this.mapper = mapper;
        this.props = props;
        this.tracer = tracer;
    }

    /** Execute only through the task's frozen allowlist. */
    public ToolExecutionOutcome execute(TaskToolCatalog catalog, ToolCall call, ToolContext ctx) {
        return executeResolved(
                catalog.get(call.name()), catalog.snapshot(call.name()), call, ctx);
    }

    private ToolExecutionOutcome executeResolved(
            Tool tool, ToolSnapshot snapshot, ToolCall call, ToolContext ctx) {
        // M6:工具 span(parent = 当前 agent.step;并发路径下由 executeConcurrently 跨虚拟线程把 context 传好)
        String observableName = observableToolName(snapshot);
        Span span = tracer.spanBuilder("execute_tool " + observableName)
                .setAttribute(Trace.TOOL_NAME, observableName)
                .setAttribute(Trace.TOOL_CALL_ID, call.id())
                .startSpan();
        if (tool != null) {
            span.setAttribute(Trace.IDEMPOTENCY, tool.idempotency().name());
        }
        if (snapshot != null) {
            span.setAttribute(Trace.TOOL_PROVIDER, snapshot.provider());
            if (snapshot.provider().startsWith("mcp:")) {
                span.setAttribute(
                        Trace.MCP_SERVER,
                        snapshot.provider().substring("mcp:".length()));
            }
        }
        try (Scope ignored = span.makeCurrent()) {
            if (tool == null) {
                span.setStatus(StatusCode.ERROR, "unknown tool");
                return withOutcome(span, ToolExecutionOutcome.definitive(
                        "错误:不存在名为 '" + call.name() + "' 的工具。"));
            }
            try {
                // 模型传来的参数是字符串形式的 JSON,先解析成节点
                String rawArgs = (call.arguments() == null || call.arguments().isBlank())
                        ? "{}" : call.arguments();
                JsonNode args = mapper.readTree(rawArgs);
                // 绑定本次调用的 idempotencyKey(=tool_call_id),供 run_command 等把 key 下推给副作用做幂等
                String result = tool.execute(args, ctx.forCall(call.id()));
                log.info("工具完成: {}", observableName);
                return withOutcome(span, ToolExecutionOutcome.definitive(result));
            } catch (InjectedWorkerCrashException | FencedExecutionException fatal) {
                throw fatal;
            } catch (RemoteOutcomeUnknownException unknown) {
                span.setStatus(StatusCode.ERROR, "remote outcome unknown");
                if (isIdempotentMcp(snapshot)) {
                    return withOutcome(
                            span,
                            ToolExecutionOutcome.remoteOutcomeUnknown(boundedUnknown(call)));
                }
                return withOutcome(span, ToolExecutionOutcome.definitive(
                        "工具 '" + call.name() + "' 执行失败:remote outcome unavailable"));
            } catch (Exception e) {
                span.setStatus(StatusCode.ERROR, "tool execution failed");
                log.warn("工具 '{}' 执行失败", observableName);
                return withOutcome(span, ToolExecutionOutcome.definitive(
                        "工具 '" + call.name() + "' 执行失败:" + e.getMessage()));
            }
        } finally {
            span.end();
        }
    }

    /**
     * 并发执行同一轮的多个工具调用,返回 tool_call_id -> 结果。
     *
     * 设计要点:
     *  1. 一个工具一根虚拟线程;每个工具独立超时,慢/卡的工具不拖垮同一轮的其它工具。
     *  2. 不管成功 / 失败 / 超时,每个 tool_call_id 在返回 map 里都【必有一条结果】——
     *     上层据此为每个 id 各回一条 tool 消息,满足 OpenAI/DeepSeek "每个 tool_call 必须有结果"的协议(否则 400)。
     *  3. 这里只并发"执行"。落库与上下文写回仍由上层串行做:message.seq 用计数生成、
     *     Context 是普通 ArrayList,都不是线程安全的;而落库很轻,真正耗时的执行已经并发掉了。
     *
     * 单个工具或显式关并发时退回串行,省掉线程开销、也便于"串行 vs 并发"对照演示。
     */
    /** Execute a batch only through the task's frozen allowlist. */
    public Map<String, ToolExecutionOutcome> executeConcurrently(
            TaskToolCatalog catalog, List<ToolCall> calls, ToolContext ctx) {
        Map<String, ToolExecutionOutcome> results = new LinkedHashMap<>();
        Context otelContext = Context.current();

        if (calls.size() <= 1 || !props.isConcurrent()) {
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (ToolCall call : calls) {
                    PendingExecution pending =
                            submit(pool, otelContext, catalog, call, ctx);
                    try {
                        results.put(
                                call.id(),
                                await(
                                        call,
                                        pending,
                                        catalog.snapshot(call.name())));
                    } catch (InjectedWorkerCrashException | FencedExecutionException fatal) {
                        pending.future().cancel(true);
                        throw fatal;
                    }
                }
            }
            return results;
        }

        // M6:OTel context 是 ThreadLocal,跨不过下面 pool.submit 的虚拟线程边界 —— 提交【前】捕获当前 context
        // (此刻 = step span),在每个工具线程里 makeCurrent 恢复,execute 开的 execute_tool span 才会正确挂到
        // step span 下。这与当初 ToolContext 选「显式捕获传参而非 ThreadLocal」是同一问题、同一解法。
        // JDK21:每任务一根虚拟线程;超时 future 先取消,再关闭本批 executor
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<ToolCall, PendingExecution> pendingExecutions = new LinkedHashMap<>();
            for (ToolCall call : calls) {
                pendingExecutions.put(
                        call, submit(pool, otelContext, catalog, call, ctx));
            }
            for (Map.Entry<ToolCall, PendingExecution> entry
                    : pendingExecutions.entrySet()) {
                ToolCall call = entry.getKey();
                try {
                    results.put(
                            call.id(),
                            await(
                                    call,
                                    entry.getValue(),
                                    catalog.snapshot(call.name())));
                } catch (InjectedWorkerCrashException | FencedExecutionException fatal) {
                    pendingExecutions.values().forEach(
                            pending -> pending.future().cancel(true));
                    throw fatal;
                }
            }
        }
        return results;
    }

    private PendingExecution submit(
            ExecutorService pool,
            Context otelContext,
            TaskToolCatalog catalog,
            ToolCall call,
            ToolContext toolContext) {
        ToolSnapshot snapshot = catalog.snapshot(call.name());
        long timeoutMs = snapshot == null ? -1 : snapshot.timeoutMs();
        long submittedAt = System.nanoTime();
        long deadlineNanos = timeoutMs < 0
                ? Long.MAX_VALUE
                : deadlineNanos(submittedAt, timeoutMs);
        Future<ToolExecutionOutcome> future = pool.submit(() -> {
            try (Scope ignored = otelContext.makeCurrent()) {
                return execute(catalog, call, toolContext);
            }
        });
        return new PendingExecution(future, timeoutMs, deadlineNanos);
    }

    private static long deadlineNanos(long submittedAt, long timeoutMs) {
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            return Math.addExact(submittedAt, timeoutNanos);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** 等单个工具的结果,带超时;超时/异常都兜成可读结果回给模型。 */
    private ToolExecutionOutcome await(
            ToolCall call, PendingExecution pending, ToolSnapshot snapshot) {
        Future<ToolExecutionOutcome> future = pending.future();
        boolean unknownOnTimeout = isIdempotentMcp(snapshot);
        String observableName = observableToolName(snapshot);
        try {
            if (pending.deadlineNanos() == Long.MAX_VALUE) {
                return future.get();
            }
            long remainingNanos = Math.max(
                    0, pending.deadlineNanos() - System.nanoTime());
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);  // 中断该工具线程(真正的硬杀留给子进程/Docker 沙箱那层)
            log.warn("工具 '{}' 执行超过 {}ms,已中断", observableName, pending.timeoutMs());
            if (unknownOnTimeout) {
                return ToolExecutionOutcome.remoteOutcomeUnknown(boundedUnknown(call));
            }
            return ToolExecutionOutcome.definitive(
                    "错误:工具 '" + call.name() + "' 执行超时(>" + pending.timeoutMs()
                            + "ms),已被中断。");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof InjectedWorkerCrashException crash) {
                throw crash;
            }
            if (cause instanceof FencedExecutionException fenced) {
                throw fenced;
            }
            if (cause instanceof RemoteOutcomeUnknownException && unknownOnTimeout) {
                return ToolExecutionOutcome.remoteOutcomeUnknown(boundedUnknown(call));
            }
            return ToolExecutionOutcome.definitive(
                    "工具 '" + call.name() + "' 执行异常:" + cause);
        } catch (InterruptedException ie) {
            future.cancel(true);   // 3b 硬杀:取消该工具的虚拟线程 -> 沙箱据中断杀掉子进程/容器(并发路径)
            Thread.currentThread().interrupt();
            return ToolExecutionOutcome.definitive(
                    "工具 '" + call.name() + "' 被强制取消打断。");
        }
    }

    private record PendingExecution(
            Future<ToolExecutionOutcome> future, long timeoutMs, long deadlineNanos) {}

    private static boolean isIdempotentMcp(ToolSnapshot snapshot) {
        return snapshot != null
                && snapshot.provider().startsWith("mcp:")
                && snapshot.idempotencyClass() == IdempotencyClass.IDEMPOTENT;
    }

    private static String observableToolName(ToolSnapshot snapshot) {
        return snapshot == null ? "unknown" : snapshot.name();
    }

    private static String boundedUnknown(ToolCall call) {
        return "Remote MCP outcome is unknown for tool " + call.name() + ".";
    }

    private static ToolExecutionOutcome withOutcome(
            Span span, ToolExecutionOutcome outcome) {
        span.setAttribute(Trace.TOOL_OUTCOME, outcome.kind().name());
        return outcome;
    }
}
