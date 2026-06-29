package com.reagent.obs;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;

/**
 * trace 辅助(M6)——集中三件事,别散在各埋点处:
 * <ol>
 *   <li><b>taskId → traceId 派生</b>:taskId 本就是 UUID(16 字节),去横杠正好是 OTel traceId 的 32 hex;</li>
 *   <li><b>跨崩溃恢复的"逻辑任务根" Context</b>:同一 taskId 每次 run(新任务 / 每次恢复)都以它为 parent,
 *       于是无论崩几次都落在同一条 trace 下 —— durable execution「一个任务一条 trace」;</li>
 *   <li><b>统一属性 key</b>:{@code reagent.*} 自定义、{@code gen_ai.*} 对齐 OTel GenAI 语义约定。</li>
 * </ol>
 */
public final class Trace {

    private Trace() {
    }

    /** instrumentation scope 名(getTracer 用)。 */
    public static final String INSTRUMENTATION_NAME = "com.reagent";

    // ===== task / step =====
    public static final AttributeKey<String> TASK_ID = AttributeKey.stringKey("reagent.task.id");
    public static final AttributeKey<String> GOAL = AttributeKey.stringKey("reagent.task.goal");
    public static final AttributeKey<Long> RECOVERY_COUNT = AttributeKey.longKey("reagent.task.recovery_count");
    public static final AttributeKey<String> TASK_STATUS = AttributeKey.stringKey("reagent.task.status");
    public static final AttributeKey<Long> STEP_NUMBER = AttributeKey.longKey("reagent.step.number");
    public static final AttributeKey<Boolean> STEP_PENDING = AttributeKey.booleanKey("reagent.step.pending");

    // ===== LLM(对齐 OTel GenAI 语义约定 gen_ai.*)=====
    public static final AttributeKey<String> GENAI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    public static final AttributeKey<String> GENAI_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    public static final AttributeKey<String> GENAI_OP = AttributeKey.stringKey("gen_ai.operation.name");
    public static final AttributeKey<Long> USAGE_IN = AttributeKey.longKey("gen_ai.usage.input_tokens");
    public static final AttributeKey<Long> USAGE_OUT = AttributeKey.longKey("gen_ai.usage.output_tokens");
    public static final AttributeKey<String> DECISION = AttributeKey.stringKey("reagent.llm.decision");
    public static final AttributeKey<Long> TOOL_CALLS = AttributeKey.longKey("reagent.llm.tool_calls");

    // ===== 工具 / 沙箱 =====
    public static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("gen_ai.tool.name");
    public static final AttributeKey<String> TOOL_CALL_ID = AttributeKey.stringKey("reagent.tool.call_id");
    public static final AttributeKey<String> IDEMPOTENCY = AttributeKey.stringKey("reagent.tool.idempotency_class");
    public static final AttributeKey<String> SANDBOX_TYPE = AttributeKey.stringKey("reagent.sandbox.type");
    public static final AttributeKey<Long> EXIT_CODE = AttributeKey.longKey("reagent.sandbox.exit_code");
    public static final AttributeKey<Boolean> KILLED = AttributeKey.booleanKey("reagent.sandbox.killed");
    public static final AttributeKey<Boolean> STARTUP_FAILED = AttributeKey.booleanKey("reagent.sandbox.startup_failed");
    public static final AttributeKey<Long> DURATION_MS = AttributeKey.longKey("reagent.sandbox.duration_ms");

    /** taskId(UUID 字符串)派生稳定 traceId:去横杠取 32 hex(UUID = 16 字节,正合 traceId 格式)。 */
    public static String traceIdFrom(String taskId) {
        String hex = taskId.replace("-", "").toLowerCase();
        if (hex.length() >= 32) {
            return hex.substring(0, 32);
        }
        // 理论不会发生(taskId 必是 UUID);兜底右补 0 凑 32,仍保持确定性
        return (hex + "0".repeat(32)).substring(0, 32);
    }

    /** 从 traceId 再派生稳定的"逻辑任务根" spanId(16 hex、非全零)。 */
    public static String rootSpanIdFrom(String traceId) {
        String sid = traceId.substring(16, 32);
        return "0000000000000000".equals(sid) ? "1111111111111111" : sid;
    }

    /**
     * 同一 taskId 的"逻辑任务根" Context:每次 run(新任务 / 每次恢复)都以它为 parent 开 root span,
     * 于是无论崩溃重启多少次,都落在同一条 traceId 下 —— 在 Jaeger 里就是一条 trace 里多次 run 的子树,
     * 直接看到「断在哪、又从哪续上」。零持久化(traceId 从 taskId 算出)。
     */
    public static Context logicalRootContext(String taskId) {
        String traceId = traceIdFrom(taskId);
        String spanId = rootSpanIdFrom(traceId);
        SpanContext root = SpanContext.createFromRemoteParent(
                traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault());
        return Context.root().with(Span.wrap(root));
    }
}
