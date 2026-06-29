package com.reagent.obs;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Trace} 纯单测(M6):验证「taskId 派生 traceId」的确定性 —— 这是「一个可恢复任务一条 trace、
 * 跨崩溃重启」的地基:新任务与每次恢复用同一 taskId 派生出同一 traceId,于是落在同一条 trace 下。
 */
class TraceTest {

    private static final String TASK = "758f6039-3ebb-4dd2-956a-8f5cce32a0f4";

    @Test
    void traceId_由taskId确定性派生_去横杠32位hex() {
        String traceId = Trace.traceIdFrom(TASK);
        assertEquals("758f60393ebb4dd2956a8f5cce32a0f4", traceId);
        assertEquals(32, traceId.length());
        assertTrue(traceId.matches("[0-9a-f]{32}"), "应是 32 位小写 hex");
    }

    @Test
    void 同taskId派生同traceId_异taskId派生异traceId() {
        String other = "00000000-0000-0000-0000-000000000001";
        assertEquals(Trace.traceIdFrom(TASK), Trace.traceIdFrom(TASK), "确定性:同 id 恒等");
        assertNotEquals(Trace.traceIdFrom(TASK), Trace.traceIdFrom(other));
    }

    @Test
    void 逻辑任务根context_traceId等于派生值_且两次派生恒等() {
        Context root = Trace.logicalRootContext(TASK);
        SpanContext sc = Span.fromContext(root).getSpanContext();
        assertTrue(sc.isValid(), "派生的逻辑根 SpanContext 应合法(可作 parent)");
        assertEquals(Trace.traceIdFrom(TASK), sc.getTraceId());

        // 关键:同一 taskId 两次派生的 traceId/spanId 恒等 = 崩溃重启续跑后仍落在【同一条 trace】
        SpanContext sc2 = Span.fromContext(Trace.logicalRootContext(TASK)).getSpanContext();
        assertEquals(sc.getTraceId(), sc2.getTraceId());
        assertEquals(sc.getSpanId(), sc2.getSpanId());
    }
}
