package com.reagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.ToolCall;
import com.reagent.obs.Trace;
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

    private final ToolRegistry registry;
    private final ObjectMapper mapper;
    private final ToolProperties props;
    private final Tracer tracer;

    public ToolExecutor(ToolRegistry registry, ObjectMapper mapper, ToolProperties props, Tracer tracer) {
        this.registry = registry;
        this.mapper = mapper;
        this.props = props;
        this.tracer = tracer;
    }

    /** 执行单个工具调用。任何异常都兜底成可读字符串,绝不抛出。 */
    public String execute(ToolCall call, ToolContext ctx) {
        Tool tool = registry.get(call.name());
        // M6:工具 span(parent = 当前 agent.step;并发路径下由 executeConcurrently 跨虚拟线程把 context 传好)
        Span span = tracer.spanBuilder("execute_tool " + call.name())
                .setAttribute(Trace.TOOL_NAME, call.name())
                .setAttribute(Trace.TOOL_CALL_ID, call.id())
                .startSpan();
        if (tool != null) {
            span.setAttribute(Trace.IDEMPOTENCY, tool.idempotency().name());
        }
        try (Scope ignored = span.makeCurrent()) {
            if (tool == null) {
                span.setStatus(StatusCode.ERROR, "unknown tool");
                return "错误:不存在名为 '" + call.name() + "' 的工具。";
            }
            try {
                // 模型传来的参数是字符串形式的 JSON,先解析成节点
                String rawArgs = (call.arguments() == null || call.arguments().isBlank())
                        ? "{}" : call.arguments();
                JsonNode args = mapper.readTree(rawArgs);
                // 绑定本次调用的 idempotencyKey(=tool_call_id),供 run_command 等把 key 下推给副作用做幂等
                String result = tool.execute(args, ctx.forCall(call.id()));
                log.info("工具返回: {} -> {}", call.name(), preview(result));
                return result;
            } catch (Exception e) {
                span.recordException(e);
                span.setStatus(StatusCode.ERROR);
                log.warn("工具 '{}' 执行失败: {}", call.name(), e.toString());
                return "工具 '" + call.name() + "' 执行失败:" + e.getMessage();
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
    public Map<String, String> executeConcurrently(List<ToolCall> calls, ToolContext ctx) {
        Map<String, String> results = new LinkedHashMap<>();

        if (calls.size() <= 1 || !props.isConcurrent()) {
            for (ToolCall c : calls) {
                results.put(c.id(), execute(c, ctx));   // 串行:同线程,execute_tool span 自动挂当前 step span
            }
            return results;
        }

        // M6:OTel context 是 ThreadLocal,跨不过下面 pool.submit 的虚拟线程边界 —— 提交【前】捕获当前 context
        // (此刻 = step span),在每个工具线程里 makeCurrent 恢复,execute 开的 execute_tool span 才会正确挂到
        // step span 下。这与当初 ToolContext 选「显式捕获传参而非 ThreadLocal」是同一问题、同一解法。
        Context otelContext = Context.current();
        // JDK21:每任务一根虚拟线程;try-with-resources 关闭时等所有任务结束
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<ToolCall, Future<String>> futures = new LinkedHashMap<>();
            for (ToolCall c : calls) {
                futures.put(c, pool.submit(() -> {
                    try (Scope ignored = otelContext.makeCurrent()) {
                        return execute(c, ctx);
                    }
                }));
            }
            for (Map.Entry<ToolCall, Future<String>> e : futures.entrySet()) {
                ToolCall c = e.getKey();
                results.put(c.id(), await(c, e.getValue()));
            }
        }
        return results;
    }

    /** 等单个工具的结果,带超时;超时/异常都兜成可读结果回给模型。 */
    private String await(ToolCall call, Future<String> future) {
        try {
            return future.get(props.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);  // 中断该工具线程(真正的硬杀留给子进程/Docker 沙箱那层)
            log.warn("工具 '{}' 执行超过 {}ms,已中断", call.name(), props.getTimeoutMs());
            return "错误:工具 '" + call.name() + "' 执行超时(>" + props.getTimeoutMs()
                    + "ms),已被中断。";
        } catch (ExecutionException ee) {
            // execute 内部已兜底,正常到不了这;纯防御
            return "工具 '" + call.name() + "' 执行异常:" + ee.getCause();
        } catch (InterruptedException ie) {
            future.cancel(true);   // 3b 硬杀:取消该工具的虚拟线程 -> 沙箱据中断杀掉子进程/容器(并发路径)
            Thread.currentThread().interrupt();
            return "工具 '" + call.name() + "' 被强制取消打断。";
        }
    }

    private static String preview(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + " ...(省略)" : s;
    }
}
