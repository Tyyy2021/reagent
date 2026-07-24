package com.reagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.core.ToolCall;
import com.reagent.core.FaultContext;
import com.reagent.core.FaultPoint;
import com.reagent.core.FencedExecutionException;
import com.reagent.core.InjectedWorkerCrashException;
import com.reagent.core.TaskRunToken;
import com.reagent.mcp.McpCallResult;
import com.reagent.mcp.McpGateway;
import com.reagent.mcp.McpProperties;
import com.reagent.mcp.McpRemoteTool;
import com.reagent.mcp.McpToolAdapter;
import com.reagent.mcp.RemoteOutcomeUnknownException;
import com.reagent.obs.Trace;
import com.reagent.persist.TaskStatus;
import com.reagent.profile.AgentProfileDefinition;
import com.reagent.profile.SchemaHasher;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.TaskToolCatalog;
import com.reagent.profile.ToolCatalogResolver;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        private final CountDownLatch concurrencyGate;
        FakeTool(String name) { this(name, null); }
        FakeTool(String name, CountDownLatch concurrencyGate) {
            this.name = name;
            this.concurrencyGate = concurrencyGate;
        }
        @Override public String name() { return name; }
        @Override public String description() { return "fake tool " + name; }
        @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
        @Override public IdempotencyClass idempotency() { return IdempotencyClass.READ_ONLY; }
        @Override public String execute(JsonNode args, ToolContext ctx) throws Exception {
            if (concurrencyGate != null) {
                concurrencyGate.countDown();
                if (!concurrencyGate.await(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("concurrent tools did not start before deadline");
                }
            }
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

        CountDownLatch concurrencyGate = new CountDownLatch(2);
        ToolRegistry registry = new ToolRegistry(List.of(
                new FakeTool("t1", concurrencyGate),
                new FakeTool("t2", concurrencyGate)));
        ObjectMapper mapper = new ObjectMapper();
        ToolProperties properties = new ToolProperties();
        ToolCatalogResolver resolver = new ToolCatalogResolver(registry, new SchemaHasher(mapper), properties);
        TaskProfileSnapshot snapshot = resolver.snapshot(new AgentProfileDefinition(
                "coding", "v1", "prompt", null, null, List.of(), List.of("t1", "t2")));
        TaskToolCatalog catalog = resolver.resolve(snapshot);
        ToolExecutor executor = new ToolExecutor(mapper, properties, tracer);

        ToolContext ctx = new ToolContext("task-1", Path.of("."));
        List<ToolCall> calls = List.of(
                new ToolCall("call_1", "t1", "{}"),
                new ToolCall("call_2", "t2", "{}"));

        // 外层 step span 设为 current(模拟 driveLoop 的层次),再并发执行两个工具
        Span step = tracer.spanBuilder("agent.step").startSpan();
        try (Scope ignored = step.makeCurrent()) {
            Map<String, ToolExecutionOutcome> results =
                    executor.executeConcurrently(catalog, calls, ctx);
            assertEquals(
                    ToolExecutionOutcome.definitive("ok:t1"),
                    results.get("call_1"));
            assertEquals(
                    ToolExecutionOutcome.definitive("ok:t2"),
                    results.get("call_2"));
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

    @Test
    void executorCannotRunToolOutsideTaskCatalog() {
        AtomicInteger executions = new AtomicInteger();
        Tool listed = new FakeTool("listed");
        Tool extra = new Tool() {
            @Override public String name() { return "extra"; }
            @Override public String description() { return "not allowlisted"; }
            @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
            @Override public String execute(JsonNode args, ToolContext ctx) {
                executions.incrementAndGet();
                return "must not run";
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        ToolProperties properties = new ToolProperties();
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(List.of(listed, extra)), new SchemaHasher(mapper), properties);
        TaskToolCatalog catalog = resolver.resolve(resolver.snapshot(new AgentProfileDefinition(
                "coding", "v1", "prompt", null, null, List.of(), List.of("listed"))));
        Tracer tracer = OpenTelemetrySdk.builder().build().getTracer("test");
        ToolExecutor executor = new ToolExecutor(mapper, properties, tracer);

        ToolExecutionOutcome result = executor.execute(catalog,
                new ToolCall("call-extra", "extra", "{}"), new ToolContext("task-1", Path.of(".")));

        assertEquals(0, executions.get());
        assertEquals(
                ToolExecutionOutcome.definitive("错误:不存在名为 'extra' 的工具。"),
                result);
    }

    @Test
    void remoteUnknownIsDistinctFromDefinitiveToolFailure() {
        Tool localLookalike = new Tool() {
            @Override public String name() { return "local_lookalike"; }
            @Override public String description() { return "local"; }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of("type", "object");
            }
            @Override public IdempotencyClass idempotency() {
                return IdempotencyClass.IDEMPOTENT;
            }
            @Override public String execute(JsonNode args, ToolContext ctx) {
                throw new RemoteOutcomeUnknownException(
                        name(), new java.io.IOException("local lookalike"));
            }
        };
        McpGateway unknownGateway = new McpGateway() {
            @Override
            public List<McpRemoteTool> discover(String serverId) {
                return List.of(new McpRemoteTool(
                        serverId,
                        "create_ticket",
                        "ticket",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "title", Map.of("type", "string"),
                                        "idempotency_key", Map.of("type", "string")),
                                "required", List.of("title", "idempotency_key"))));
            }

            @Override
            public McpCallResult call(
                    String serverId, String toolName, Map<String, Object> arguments) {
                throw new RemoteOutcomeUnknownException(
                        toolName, new java.io.IOException("remote connection lost"));
            }

            @Override
            public void close() {}
        };
        ObjectMapper mapper = new ObjectMapper();
        ToolProperties properties = new ToolProperties();
        McpProperties mcpProperties = mcpProperties();
        McpToolAdapter remote = new McpToolAdapter(
                unknownGateway, mcpProperties, mapper, "fake-ops", "create_ticket");
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(List.of(remote, localLookalike)),
                new SchemaHasher(mapper),
                properties,
                mcpProperties);
        TaskToolCatalog catalog = resolver.resolve(resolver.snapshot(new AgentProfileDefinition(
                "test",
                "v1",
                "system",
                null,
                null,
                List.of("fake-ops"),
                List.of(remote.name(), localLookalike.name()))));
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        ToolExecutor executor = new ToolExecutor(
                mapper,
                properties,
                OpenTelemetrySdk.builder()
                        .setTracerProvider(provider)
                        .build()
                        .getTracer("test"));
        ToolContext context = new ToolContext("task-1", Path.of("."));

        assertEquals(
                ToolExecutionOutcome.Kind.REMOTE_OUTCOME_UNKNOWN,
                executor.execute(
                                catalog,
                                new ToolCall(
                                        "call-unknown",
                                        remote.name(),
                                        "{\"title\":\"incident\"}"),
                                context)
                        .kind());
        assertEquals(
                ToolExecutionOutcome.Kind.DEFINITIVE,
                executor.execute(
                                catalog,
                                new ToolCall(
                                        "call-local", localLookalike.name(), "{}"),
                                context)
                        .kind());

        SpanData remoteSpan = exporter.getFinishedSpanItems().stream()
                .filter(span -> span.getName().equals("execute_tool create_ticket"))
                .findFirst()
                .orElseThrow();
        assertEquals("mcp:fake-ops", remoteSpan.getAttributes().get(Trace.TOOL_PROVIDER));
        assertEquals("fake-ops", remoteSpan.getAttributes().get(Trace.MCP_SERVER));
        assertEquals(
                "REMOTE_OUTCOME_UNKNOWN",
                remoteSpan.getAttributes().get(Trace.TOOL_OUTCOME));
        assertTrue(remoteSpan.getAttributes().asMap().keySet().stream()
                .noneMatch(key -> key.getKey().contains("argument")
                        || key.getKey().contains("url")
                        || key.getKey().contains("result")));
        provider.close();
    }

    @Test
    void fatalCrashAndFenceSignalsEscapeWithoutTextConversionAndCancelSiblings() {
        TaskRunToken token = new TaskRunToken("task-1", "worker-a", 7);
        InjectedWorkerCrashException crash = new InjectedWorkerCrashException(
                FaultPoint.AFTER_REMOTE_SIDE_EFFECT_BEFORE_LOCAL_RESULT,
                new FaultContext(
                        token.taskId(),
                        token.workerId(),
                        token.leaseEpoch(),
                        java.util.Optional.of("call-crash"),
                        java.util.Optional.empty()));
        FencedExecutionException fenced =
                new FencedExecutionException(token, "worker-b", 8, TaskStatus.RUNNING);
        CountDownLatch siblingStarted = new CountDownLatch(1);
        AtomicBoolean siblingCancelled = new AtomicBoolean();
        Tool crashTool = throwingTool("crash", () -> {
            if (!siblingStarted.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("sibling did not start");
            }
            throw crash;
        });
        Tool sibling = throwingTool("sibling", () -> {
            siblingStarted.countDown();
            try {
                new CountDownLatch(1).await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                siblingCancelled.set(true);
                Thread.currentThread().interrupt();
            }
            return "sibling";
        });
        Tool fenceTool = throwingTool("fence", () -> {
            throw fenced;
        });
        ObjectMapper mapper = new ObjectMapper();
        ToolProperties properties = new ToolProperties();
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(List.of(crashTool, sibling, fenceTool)),
                new SchemaHasher(mapper),
                properties);
        TaskToolCatalog catalog = resolver.resolve(resolver.snapshot(new AgentProfileDefinition(
                "fatal",
                "v1",
                "system",
                null,
                null,
                List.of(),
                List.of(crashTool.name(), sibling.name(), fenceTool.name()))));
        ToolExecutor executor = new ToolExecutor(
                mapper,
                properties,
                OpenTelemetrySdk.builder().build().getTracer("test"));
        ToolContext context = new ToolContext(token, Path.of("."));

        InjectedWorkerCrashException observedCrash = assertThrows(
                InjectedWorkerCrashException.class,
                () -> executor.executeConcurrently(
                        catalog,
                        List.of(
                                new ToolCall("call-crash", "crash", "{}"),
                                new ToolCall("call-sibling", "sibling", "{}")),
                        context));
        assertSame(crash, observedCrash);
        assertTrue(siblingCancelled.get());

        FencedExecutionException observedFence = assertThrows(
                FencedExecutionException.class,
                () -> executor.execute(
                        catalog, new ToolCall("call-fence", "fence", "{}"), context));
        assertSame(fenced, observedFence);
    }

    @Test
    void outerTimeoutOfIdempotentMcpProviderIsUnknownWhileLocalSiblingIsDefinitive() {
        ObjectMapper mapper = new ObjectMapper();
        ToolProperties toolProperties = new ToolProperties();
        toolProperties.setTimeoutMs(50);
        CountDownLatch remoteStarted = new CountDownLatch(1);
        AtomicBoolean remoteCancelled = new AtomicBoolean();
        McpGateway gateway = new McpGateway() {
            @Override
            public List<McpRemoteTool> discover(String serverId) {
                return List.of(new McpRemoteTool(
                        serverId,
                        "create_ticket",
                        "ticket",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "title", Map.of("type", "string"),
                                        "idempotency_key", Map.of("type", "string")),
                                "required", List.of("title", "idempotency_key"))));
            }

            @Override
            public McpCallResult call(
                    String serverId, String toolName, Map<String, Object> arguments) {
                remoteStarted.countDown();
                try {
                    new CountDownLatch(1).await(2, TimeUnit.SECONDS);
                    return new McpCallResult("unexpected", false);
                } catch (InterruptedException ex) {
                    remoteCancelled.set(true);
                    Thread.currentThread().interrupt();
                    throw new RemoteOutcomeUnknownException(toolName, ex);
                }
            }

            @Override
            public void close() {}
        };
        McpProperties properties = mcpProperties();
        McpToolAdapter ticket =
                new McpToolAdapter(gateway, properties, mapper, "fake-ops", "create_ticket");
        Tool local = new FakeTool("local");
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(List.of(ticket, local)),
                new SchemaHasher(mapper),
                toolProperties,
                properties);
        TaskToolCatalog catalog = resolver.resolve(resolver.snapshot(new AgentProfileDefinition(
                "mcp-timeout",
                "v1",
                "system",
                null,
                null,
                List.of("fake-ops"),
                List.of(ticket.name(), local.name()))));
        ToolExecutor executor = new ToolExecutor(
                mapper,
                toolProperties,
                OpenTelemetrySdk.builder().build().getTracer("test"));

        Map<String, ToolExecutionOutcome> outcomes = executor.executeConcurrently(
                catalog,
                List.of(
                        new ToolCall("call-ticket", "create_ticket", "{\"title\":\"incident\"}"),
                        new ToolCall("call-local", "local", "{}")),
                new ToolContext("task-1", Path.of(".")));

        assertTrue(awaitLatch(remoteStarted));
        assertEquals(
                ToolExecutionOutcome.Kind.REMOTE_OUTCOME_UNKNOWN,
                outcomes.get("call-ticket").kind());
        assertEquals(
                ToolExecutionOutcome.definitive("ok:local"),
                outcomes.get("call-local"));
        assertTrue(remoteCancelled.get());
    }

    private static Tool throwingTool(String name, ThrowingAction action) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parameterSchema() {
                return Map.of("type", "object");
            }
            @Override public String execute(JsonNode args, ToolContext ctx) throws Exception {
                return action.run();
            }
        };
    }

    private static McpProperties mcpProperties() {
        McpProperties.ToolPolicy ticketPolicy = new McpProperties.ToolPolicy();
        ticketPolicy.setIdempotencyClass(IdempotencyClass.IDEMPOTENT);
        ticketPolicy.setApprovalPolicy(ApprovalPolicy.REQUIRE_APPROVAL);
        McpProperties.Server server = new McpProperties.Server();
        server.setBaseUrl("http://localhost:8090");
        server.setEndpoint("/mcp");
        server.setConnectTimeout(java.time.Duration.ofMillis(500));
        server.setRequestTimeout(java.time.Duration.ofSeconds(3));
        server.setMaximumResponseBytes(65_536);
        server.setTools(Map.of("create_ticket", ticketPolicy));
        McpProperties properties = new McpProperties();
        properties.setServers(Map.of("fake-ops", server));
        properties.validate();
        return properties;
    }

    private static boolean awaitLatch(CountDownLatch latch) {
        try {
            return latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        String run() throws Exception;
    }
}
