package com.reagent.tool;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            @Override public String name() { return "SECRET_UNKNOWN_TOOL"; }
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
        try {
            ToolExecutionOutcome result = executor.execute(
                    catalog,
                    new ToolCall("call-extra", extra.name(), "{}"),
                    new ToolContext("task-1", Path.of(".")));

            assertEquals(0, executions.get());
            assertEquals(
                    ToolExecutionOutcome.definitive(
                            "错误:不存在名为 'SECRET_UNKNOWN_TOOL' 的工具。"),
                    result);
            SpanData toolSpan = exporter.getFinishedSpanItems().getFirst();
            assertEquals("execute_tool unknown", toolSpan.getName());
            assertEquals(
                    "unknown",
                    toolSpan.getAttributes().get(Trace.TOOL_NAME));
            assertEquals(
                    "call-extra",
                    toolSpan.getAttributes().get(Trace.TOOL_CALL_ID));
            assertFalse((toolSpan.getAttributes() + " " + toolSpan.getEvents())
                    .contains("SECRET_UNKNOWN_TOOL"));
        } finally {
            provider.close();
        }
    }

    @Test
    void remoteUnknownIsDistinctFromDefinitiveToolFailure() {
        String resultSentinel = "MCP_RESULT_BODY_SECRET_SENTINEL";
        String remoteCauseSentinel = "MCP_REMOTE_CAUSE_SECRET_SENTINEL";
        String localCauseSentinel = "LOCAL_REMOTE_CAUSE_SECRET_SENTINEL";
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
                        name(), new java.io.IOException(localCauseSentinel));
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
                if ("return-result".equals(arguments.get("title"))) {
                    return new McpCallResult(resultSentinel, false);
                }
                throw new RemoteOutcomeUnknownException(
                        toolName, new java.io.IOException(remoteCauseSentinel));
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
        ListAppender<ILoggingEvent> logs = captureLogs(ToolExecutor.class);
        try {
            assertEquals(
                    ToolExecutionOutcome.definitive(resultSentinel),
                    executor.execute(
                            catalog,
                            new ToolCall(
                                    "call-result",
                                    remote.name(),
                                    "{\"title\":\"return-result\"}"),
                            context));
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
                    .filter(span -> "REMOTE_OUTCOME_UNKNOWN".equals(
                            span.getAttributes().get(Trace.TOOL_OUTCOME)))
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
            String exported = exporter.getFinishedSpanItems().stream()
                    .map(span -> span.getAttributes() + " " + span.getEvents())
                    .reduce("", (left, right) -> left + right);
            assertFalse(exported.contains(resultSentinel));
            assertFalse(exported.contains(remoteCauseSentinel));
            assertFalse(exported.contains(localCauseSentinel));
            String captured = capturedLogText(logs);
            assertFalse(captured.contains(resultSentinel));
            assertFalse(captured.contains(remoteCauseSentinel));
            assertFalse(captured.contains(localCauseSentinel));
        } finally {
            detachLogs(ToolExecutor.class, logs);
            provider.close();
        }
    }

    @Test
    void fatalCrashAndFenceSignalsEscapeWithoutTextConversionAndCancelSiblings()
            throws Exception {
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
        CountDownLatch siblingCancelled = new CountDownLatch(1);
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
                siblingCancelled.countDown();
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
        assertTrue(siblingCancelled.await(1, TimeUnit.SECONDS));

        FencedExecutionException observedFence = assertThrows(
                FencedExecutionException.class,
                () -> executor.execute(
                        catalog, new ToolCall("call-fence", "fence", "{}"), context));
        assertSame(fenced, observedFence);
    }

    @Test
    void singleIdempotentMcpUsesFrozenDeadlineAndTimesOutAsUnknown() {
        ObjectMapper mapper = new ObjectMapper();
        ToolProperties toolProperties = new ToolProperties();
        toolProperties.setTimeoutMs(5_000);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        McpProperties properties = mcpProperties(
                Duration.ofMillis(35),
                Map.of("create_ticket", IdempotencyClass.IDEMPOTENT));
        McpToolAdapter ticket = new McpToolAdapter(
                blockingGateway(
                        List.of(remoteTool("fake-ops", "create_ticket")),
                        started,
                        cancelled,
                        250),
                properties,
                mapper,
                "fake-ops",
                "create_ticket");
        TaskToolCatalog catalog = catalog(
                mapper,
                toolProperties,
                properties,
                List.of(ticket),
                List.of("fake-ops"),
                List.of(ticket.name()));
        toolProperties.setTimeoutMs(10_000);
        ToolExecutor executor = new ToolExecutor(
                mapper,
                toolProperties,
                OpenTelemetrySdk.builder().build().getTracer("test"));

        Map<String, ToolExecutionOutcome> outcomes = executor.executeConcurrently(
                catalog,
                List.of(new ToolCall(
                        "call-ticket", "create_ticket", "{\"title\":\"incident\"}")),
                new ToolContext("task-1", Path.of(".")));

        assertTrue(awaitLatch(started));
        assertEquals(35, catalog.snapshot("create_ticket").timeoutMs());
        assertEquals(
                ToolExecutionOutcome.Kind.REMOTE_OUTCOME_UNKNOWN,
                outcomes.get("call-ticket").kind());
        assertTrue(cancelled.get());
    }

    @Test
    void singleReadOnlyMcpUsesFrozenDeadlineAndTimesOutDefinitively() {
        ObjectMapper mapper = new ObjectMapper();
        ToolProperties toolProperties = new ToolProperties();
        toolProperties.setTimeoutMs(5_000);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        McpProperties properties = mcpProperties(
                Duration.ofMillis(35),
                Map.of("query_metrics", IdempotencyClass.READ_ONLY));
        McpToolAdapter metrics = new McpToolAdapter(
                blockingGateway(
                        List.of(remoteTool("fake-ops", "query_metrics")),
                        started,
                        cancelled,
                        250),
                properties,
                mapper,
                "fake-ops",
                "query_metrics");
        TaskToolCatalog catalog = catalog(
                mapper,
                toolProperties,
                properties,
                List.of(metrics),
                List.of("fake-ops"),
                List.of(metrics.name()));
        toolProperties.setTimeoutMs(10_000);
        ToolExecutor executor = new ToolExecutor(
                mapper,
                toolProperties,
                OpenTelemetrySdk.builder().build().getTracer("test"));

        Map<String, ToolExecutionOutcome> outcomes = executor.executeConcurrently(
                catalog,
                List.of(new ToolCall("call-metrics", "query_metrics", "{}")),
                new ToolContext("task-1", Path.of(".")));

        assertTrue(awaitLatch(started));
        assertEquals(35, catalog.snapshot("query_metrics").timeoutMs());
        assertEquals(
                ToolExecutionOutcome.Kind.DEFINITIVE,
                outcomes.get("call-metrics").kind());
        assertTrue(outcomes.get("call-metrics").content().contains(">35ms"));
        assertTrue(cancelled.get());
    }

    @Test
    void singleLocalToolUsesFrozenLocalDeadlineAfterGlobalConfigurationChanges() {
        ObjectMapper mapper = new ObjectMapper();
        ToolProperties toolProperties = new ToolProperties();
        toolProperties.setTimeoutMs(35);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        Tool local = throwingTool("local_slow", () -> {
            started.countDown();
            try {
                new CountDownLatch(1).await(250, TimeUnit.MILLISECONDS);
                return "unexpected";
            } catch (InterruptedException ex) {
                cancelled.set(true);
                Thread.currentThread().interrupt();
                return "cancelled";
            }
        });
        TaskToolCatalog catalog = catalog(
                mapper,
                toolProperties,
                null,
                List.of(local),
                List.of(),
                List.of(local.name()));
        toolProperties.setTimeoutMs(10_000);
        ToolExecutor executor = new ToolExecutor(
                mapper,
                toolProperties,
                OpenTelemetrySdk.builder().build().getTracer("test"));

        Map<String, ToolExecutionOutcome> outcomes = executor.executeConcurrently(
                catalog,
                List.of(new ToolCall("call-local", local.name(), "{}")),
                new ToolContext("task-1", Path.of(".")));

        assertTrue(awaitLatch(started));
        assertEquals(35, catalog.snapshot(local.name()).timeoutMs());
        assertEquals(
                ToolExecutionOutcome.Kind.DEFINITIVE,
                outcomes.get("call-local").kind());
        assertTrue(outcomes.get("call-local").content().contains(">35ms"));
        assertTrue(cancelled.get());
    }

    @Test
    void concurrentCallsUseIndependentFrozenDeadlinesMeasuredFromSubmission() {
        ObjectMapper mapper = new ObjectMapper();
        ToolProperties toolProperties = new ToolProperties();
        toolProperties.setTimeoutMs(200);
        CountDownLatch localStarted = new CountDownLatch(1);
        CountDownLatch remoteStarted = new CountDownLatch(1);
        AtomicBoolean remoteCancelled = new AtomicBoolean();
        McpProperties properties = mcpProperties(
                Duration.ofMillis(30),
                Map.of("create_ticket", IdempotencyClass.IDEMPOTENT));
        McpToolAdapter ticket = new McpToolAdapter(
                blockingGateway(
                        List.of(remoteTool("fake-ops", "create_ticket")),
                        remoteStarted,
                        remoteCancelled,
                        300),
                properties,
                mapper,
                "fake-ops",
                "create_ticket");
        Tool local = throwingTool("local_leader", () -> {
            localStarted.countDown();
            if (!remoteStarted.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("remote tool did not start");
            }
            new CountDownLatch(1).await(100, TimeUnit.MILLISECONDS);
            return "local-ok";
        });
        TaskToolCatalog catalog = catalog(
                mapper,
                toolProperties,
                properties,
                List.of(local, ticket),
                List.of("fake-ops"),
                List.of(local.name(), ticket.name()));
        assertEquals(200, catalog.snapshot(local.name()).timeoutMs());
        assertEquals(30, catalog.snapshot(ticket.name()).timeoutMs());
        toolProperties.setTimeoutMs(5_000);
        ToolExecutor executor = new ToolExecutor(
                mapper,
                toolProperties,
                OpenTelemetrySdk.builder().build().getTracer("test"));

        Map<String, ToolExecutionOutcome> outcomes = executor.executeConcurrently(
                catalog,
                List.of(
                        new ToolCall("call-local", local.name(), "{}"),
                        new ToolCall(
                                "call-ticket",
                                "create_ticket",
                                "{\"title\":\"incident\"}")),
                new ToolContext("task-1", Path.of(".")));

        assertTrue(awaitLatch(localStarted));
        assertTrue(awaitLatch(remoteStarted));
        assertEquals(
                ToolExecutionOutcome.definitive("local-ok"),
                outcomes.get("call-local"));
        assertEquals(
                ToolExecutionOutcome.Kind.REMOTE_OUTCOME_UNKNOWN,
                outcomes.get("call-ticket").kind());
        assertTrue(remoteCancelled.get());
    }

    @Test
    void serialDeadlineReturnsWhileTimedOutToolIgnoresInterrupt() throws Exception {
        assertDeadlineReturnsWhileToolIgnoresInterrupt(false);
    }

    @Test
    void concurrentDeadlineReturnsWhileTimedOutToolIgnoresInterrupt() throws Exception {
        assertDeadlineReturnsWhileToolIgnoresInterrupt(true);
    }

    private static void assertDeadlineReturnsWhileToolIgnoresInterrupt(
            boolean concurrent) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ToolProperties properties = new ToolProperties();
        properties.setTimeoutMs(35);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Tool stubborn = throwingTool("stubborn", () -> {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    interrupted.countDown();
                }
            }
            return "late";
        });
        Tool fast = new FakeTool("fast");
        List<Tool> tools = concurrent
                ? List.of(stubborn, fast)
                : List.of(stubborn);
        List<ToolCall> calls = concurrent
                ? List.of(
                        new ToolCall("call-stubborn", stubborn.name(), "{}"),
                        new ToolCall("call-fast", fast.name(), "{}"))
                : List.of(new ToolCall("call-stubborn", stubborn.name(), "{}"));
        TaskToolCatalog catalog = catalog(
                mapper,
                properties,
                null,
                tools,
                List.of(),
                tools.stream().map(Tool::name).toList());
        ToolExecutor executor = new ToolExecutor(
                mapper,
                properties,
                OpenTelemetrySdk.builder().build().getTracer("test"));
        FutureTask<Map<String, ToolExecutionOutcome>> batch =
                new FutureTask<>(() -> executor.executeConcurrently(
                        catalog,
                        calls,
                        new ToolContext("task-1", Path.of("."))));
        Thread driver = Thread.ofPlatform().daemon(true).unstarted(batch);
        driver.start();
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Map<String, ToolExecutionOutcome> outcomes =
                    batch.get(500, TimeUnit.MILLISECONDS);
            assertTrue(outcomes.get("call-stubborn").content().contains(">35ms"));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            if (concurrent) {
                assertEquals(
                        ToolExecutionOutcome.definitive("ok:fast"),
                        outcomes.get("call-fast"));
            }
        } finally {
            release.countDown();
            driver.join(1_000);
        }
        assertFalse(driver.isAlive());
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

    private static TaskToolCatalog catalog(
            ObjectMapper mapper,
            ToolProperties toolProperties,
            McpProperties mcpProperties,
            List<Tool> tools,
            List<String> serverIds,
            List<String> toolNames) {
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(tools),
                new SchemaHasher(mapper),
                toolProperties,
                mcpProperties);
        return resolver.resolve(resolver.snapshot(new AgentProfileDefinition(
                "timeout-test",
                "v1",
                "system",
                null,
                null,
                serverIds,
                toolNames)));
    }

    private static McpGateway blockingGateway(
            List<McpRemoteTool> remoteTools,
            CountDownLatch started,
            AtomicBoolean cancelled,
            long fallbackMs) {
        return new McpGateway() {
            @Override
            public List<McpRemoteTool> discover(String serverId) {
                return remoteTools;
            }

            @Override
            public McpCallResult call(
                    String serverId, String toolName, Map<String, Object> arguments) {
                started.countDown();
                try {
                    new CountDownLatch(1).await(fallbackMs, TimeUnit.MILLISECONDS);
                    return new McpCallResult("unexpected", false);
                } catch (InterruptedException ex) {
                    cancelled.set(true);
                    Thread.currentThread().interrupt();
                    throw new RemoteOutcomeUnknownException(toolName, ex);
                }
            }

            @Override
            public void close() {}
        };
    }

    private static McpRemoteTool remoteTool(String serverId, String toolName) {
        return new McpRemoteTool(
                serverId,
                toolName,
                toolName,
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title", Map.of("type", "string"),
                                "idempotency_key", Map.of("type", "string")),
                        "required", List.of("title", "idempotency_key")));
    }

    private static McpProperties mcpProperties() {
        return mcpProperties(
                Duration.ofSeconds(3),
                Map.of("create_ticket", IdempotencyClass.IDEMPOTENT));
    }

    private static McpProperties mcpProperties(
            Duration requestTimeout, Map<String, IdempotencyClass> toolClasses) {
        McpProperties.Server server = new McpProperties.Server();
        server.setBaseUrl("http://localhost:8090");
        server.setEndpoint("/mcp");
        server.setConnectTimeout(Duration.ofMillis(500));
        server.setRequestTimeout(requestTimeout);
        server.setMaximumResponseBytes(65_536);
        Map<String, McpProperties.ToolPolicy> policies = new java.util.LinkedHashMap<>();
        toolClasses.forEach((name, idempotency) -> {
            McpProperties.ToolPolicy policy = new McpProperties.ToolPolicy();
            policy.setIdempotencyClass(idempotency);
            policy.setApprovalPolicy(
                    idempotency == IdempotencyClass.IDEMPOTENT
                            ? ApprovalPolicy.REQUIRE_APPROVAL
                            : ApprovalPolicy.NONE);
            policies.put(name, policy);
        });
        server.setTools(policies);
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

    private static ListAppender<ILoggingEvent> captureLogs(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogs(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        appender.stop();
    }

    private static String capturedLogText(ListAppender<ILoggingEvent> appender) {
        StringBuilder captured = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            captured.append(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                captured.append(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
        }
        return captured.toString();
    }

    @FunctionalInterface
    private interface ThrowingAction {
        String run() throws Exception;
    }
}
