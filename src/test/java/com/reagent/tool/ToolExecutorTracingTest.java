package com.reagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.ToolCall;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M6 最值钱的单测:并发工具执行跨【虚拟线程】的 OTel context 传播。
 *
 * <p>OTel 的 {@code Context} 是 ThreadLocal,跨不过 {@code pool.submit} 起的虚拟线程。本测用
 * {@link InMemorySpanExporter} 抓 span,断言两个并发 {@code execute_tool} span 都正确挂在外层
 * {@code agent.step} 之下(traceId 一致、parent = step span)—— 证明 context 真跨过了 VT 边界,
 * 没断成无父的孤儿 span。这正是 §M6 难点① 的回归保护。</p>
 */
class ToolExecutorTracingTest {

    /** 假工具:睡一会儿(制造并发时间重叠),返回固定串。READ_ONLY 免得牵动幂等逻辑。 */
    private static final class FakeTool implements Tool {
        private final String name;
        FakeTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "fake tool " + name; }
        @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
        @Override public IdempotencyClass idempotency() { return IdempotencyClass.READ_ONLY; }
        @Override public String execute(JsonNode args, ToolContext ctx) throws Exception {
            Thread.sleep(50);   // 让两个工具 span 时间重叠 = 真并发
            return "ok:" + name;
        }
    }

    @Test
    void 并发工具执行_span跨虚拟线程正确挂到外层step下() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tp = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        Tracer tracer = OpenTelemetrySdk.builder().setTracerProvider(tp).build().getTracer("test");

        ToolRegistry registry = new ToolRegistry(List.of(new FakeTool("t1"), new FakeTool("t2")));
        ToolExecutor executor = new ToolExecutor(registry, new ObjectMapper(), new ToolProperties(), tracer);

        ToolContext ctx = new ToolContext("task-1", Path.of("."));
        List<ToolCall> calls = List.of(
                new ToolCall("call_1", "t1", "{}"),
                new ToolCall("call_2", "t2", "{}"));

        // 外层 step span 设为 current(模拟 driveLoop 的层次),再并发执行两个工具
        Span step = tracer.spanBuilder("agent.step").startSpan();
        try (Scope ignored = step.makeCurrent()) {
            Map<String, String> results = executor.executeConcurrently(calls, ctx);
            assertEquals("ok:t1", results.get("call_1"));
            assertEquals("ok:t2", results.get("call_2"));
        } finally {
            step.end();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData stepSpan = spans.stream()
                .filter(s -> s.getName().equals("agent.step")).findFirst().orElseThrow();
        List<SpanData> toolSpans = spans.stream()
                .filter(s -> s.getName().startsWith("execute_tool")).toList();

        assertEquals(2, toolSpans.size(), "两个工具各产生一个 execute_tool span");
        for (SpanData tool : toolSpans) {
            assertEquals(stepSpan.getTraceId(), tool.getTraceId(),
                    "跨虚拟线程后 traceId 应与外层 step 一致(context 传过去了)");
            assertEquals(stepSpan.getSpanId(), tool.getParentSpanId(),
                    "execute_tool 的 parent 应是 step span —— 证明 OTel context 跨过了 pool.submit 的 VT 边界");
        }
    }
}
