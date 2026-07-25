package com.reagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.persist.StateStore;
import com.reagent.persist.ToolCallStatus;
import com.reagent.profile.AgentProfileDefinition;
import com.reagent.profile.SchemaHasher;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.TaskToolCatalog;
import com.reagent.profile.ToolCatalogResolver;
import com.reagent.stream.StreamTransport;
import com.reagent.tool.ApprovalPolicy;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolExecutionOutcome;
import com.reagent.tool.ToolExecutor;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolBatchApprovalTest {

    private static final TaskRunToken TOKEN =
            new TaskRunToken("task-approval-batch", "worker-a", 11);

    @ParameterizedTest(name = "approval-required call first={0}")
    @ValueSource(booleans = {true, false})
    void wholeBatchWaitsBeforeAnyCallAndRepeatedEntryStillExecutesNothing(
            boolean approvalFirst,
            @TempDir Path workspace
    ) {
        ToolCall read = new ToolCall("call-read", "query_metrics", "{}");
        ToolCall ticket = new ToolCall(
                "call-ticket",
                "create_ticket",
                "{\"title\":\"Database unavailable\",\"severity\":\"sev1\","
                        + "\"evidence\":\"pool exhausted\"}");
        List<ToolCall> calls = approvalFirst
                ? List.of(ticket, read)
                : List.of(read, ticket);
        TaskToolCatalog catalog = catalog(
                new TestTool(
                        read.name(), ApprovalPolicy.NONE, IdempotencyClass.READ_ONLY),
                new TestTool(
                        ticket.name(),
                        ApprovalPolicy.REQUIRE_APPROVAL,
                        IdempotencyClass.IDEMPOTENT));

        StateStore stateStore = mock(StateStore.class, invocation -> {
            if ("prepareApprovalBarrier".equals(invocation.getMethod().getName())) {
                return true;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        calls.forEach(call ->
                when(stateStore.statusOf(TOKEN, call.id())).thenReturn(ToolCallStatus.PENDING));
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.executeConcurrently(
                        same(catalog), anyList(), any(ToolContext.class)))
                .thenAnswer(invocation -> {
                    Map<String, ToolExecutionOutcome> outcomes = new LinkedHashMap<>();
                    for (ToolCall call : invocation.<List<ToolCall>>getArgument(1)) {
                        outcomes.put(call.id(), ToolExecutionOutcome.definitive("unexpected"));
                    }
                    return outcomes;
                });
        StreamTransport transport = mock(StreamTransport.class);
        ToolBatchCoordinator coordinator = new DefaultToolBatchCoordinator(
                stateStore,
                executor,
                transport,
                new TaskControl(),
                FaultInjector.none());
        ToolContext toolContext = new ToolContext(TOKEN, workspace);
        Context context = new Context("system");

        BatchDisposition first =
                coordinator.process(TOKEN, toolContext, context, catalog, calls);
        BatchDisposition repeated =
                coordinator.process(TOKEN, toolContext, context, catalog, calls);

        assertEquals(BatchDisposition.WAITING_APPROVAL, first);
        assertEquals(BatchDisposition.WAITING_APPROVAL, repeated);
        verifyNoInteractions(executor);
        verify(stateStore, never()).markInProgress(eq(TOKEN), org.mockito.ArgumentMatchers.any());
        verify(stateStore, never()).recordToolResult(
                eq(TOKEN), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        assertEquals(1, context.size(), "waiting must not append synthetic or execution results");
    }

    private static TaskToolCatalog catalog(TestTool... tools) {
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(Arrays.asList(tools)),
                new SchemaHasher(new ObjectMapper()),
                new ToolProperties());
        TaskProfileSnapshot snapshot = resolver.snapshot(new AgentProfileDefinition(
                "test",
                "v1",
                "system",
                null,
                null,
                List.of(),
                Arrays.stream(tools).map(TestTool::name).toList()));
        return resolver.resolve(snapshot);
    }

    private record TestTool(
            String name,
            ApprovalPolicy approvalPolicy,
            IdempotencyClass idempotency
    ) implements Tool {
        @Override
        public String description() {
            return "test " + name;
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(JsonNode args, ToolContext ctx) {
            throw new AssertionError("The mocked executor owns execution in this test");
        }
    }
}
