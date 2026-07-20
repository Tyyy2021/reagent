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
import com.reagent.sandbox.RunJournal;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
import com.reagent.tool.IdempotencyClass;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolExecutor;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolBatchCoordinatorTest {

    @Test
    void pendingCallMarksInProgressExecutesOnceAndRecordsDoneWithToolMessage(@TempDir Path workspace) {
        ClassifiedTool read = new ClassifiedTool("read", IdempotencyClass.READ_ONLY);
        TaskToolCatalog catalog = catalog(read);
        ToolCall call = new ToolCall("call-pending", read.name(), "{}");
        Fixture fixture = fixture(workspace);
        when(fixture.stateStore.statusOf(TOKEN, call.id())).thenReturn(ToolCallStatus.PENDING);
        when(fixture.executor.executeConcurrently(same(catalog), eq(List.of(call)), same(fixture.toolContext)))
                .thenReturn(Map.of(call.id(), "pending-result"));

        BatchDisposition disposition = fixture.coordinator.process(
                TOKEN, fixture.toolContext, fixture.context, catalog, List.of(call));

        assertEquals(BatchDisposition.EXECUTED, disposition);
        InOrder durableOrder = inOrder(fixture.stateStore, fixture.transport, fixture.executor);
        durableOrder.verify(fixture.stateStore).markInProgress(TOKEN, call);
        durableOrder.verify(fixture.transport).publish(TOKEN, TaskEvent.Type.TOOL_CALL, Map.of(
                "id", call.id(), "name", call.name(), "arguments", call.arguments()));
        durableOrder.verify(fixture.executor)
                .executeConcurrently(same(catalog), eq(List.of(call)), same(fixture.toolContext));
        durableOrder.verify(fixture.stateStore).recordToolResult(TOKEN, call, "pending-result");
        durableOrder.verify(fixture.transport).publish(TOKEN, TaskEvent.Type.TOOL_RESULT, Map.of(
                "id", call.id(), "name", call.name(), "result", "pending-result"));
        assertToolMessages(fixture.context, List.of(call.id()), List.of("pending-result"));
    }

    @Test
    void inProgressReadOnlyAndIdempotentCallsReplay(@TempDir Path workspace) {
        ClassifiedTool read = new ClassifiedTool("read", IdempotencyClass.READ_ONLY);
        ClassifiedTool write = new ClassifiedTool("write", IdempotencyClass.IDEMPOTENT);
        TaskToolCatalog catalog = catalog(read, write);
        ToolCall readCall = new ToolCall("call-read-replay", read.name(), "{}");
        ToolCall writeCall = new ToolCall("call-write-replay", write.name(), "{}");
        List<ToolCall> calls = List.of(readCall, writeCall);
        Fixture fixture = fixture(workspace);
        when(fixture.stateStore.statusOf(TOKEN, readCall.id())).thenReturn(ToolCallStatus.IN_PROGRESS);
        when(fixture.stateStore.statusOf(TOKEN, writeCall.id())).thenReturn(ToolCallStatus.IN_PROGRESS);
        when(fixture.executor.executeConcurrently(same(catalog), eq(calls), same(fixture.toolContext)))
                .thenReturn(Map.of(readCall.id(), "read-result", writeCall.id(), "write-result"));

        fixture.coordinator.process(TOKEN, fixture.toolContext, fixture.context, catalog, calls);

        verify(fixture.stateStore).markInProgress(TOKEN, readCall);
        verify(fixture.stateStore).markInProgress(TOKEN, writeCall);
        verify(fixture.executor).executeConcurrently(same(catalog), eq(calls), same(fixture.toolContext));
        verify(fixture.stateStore).recordToolResult(TOKEN, readCall, "read-result");
        verify(fixture.stateStore).recordToolResult(TOKEN, writeCall, "write-result");
        InOrder eventOrder = inOrder(fixture.transport);
        eventOrder.verify(fixture.transport).publish(TOKEN, TaskEvent.Type.TOOL_CALL, Map.of(
                "id", readCall.id(), "name", readCall.name(), "arguments", readCall.arguments()));
        eventOrder.verify(fixture.transport).publish(TOKEN, TaskEvent.Type.TOOL_CALL, Map.of(
                "id", writeCall.id(), "name", writeCall.name(), "arguments", writeCall.arguments()));
        eventOrder.verify(fixture.transport).publish(TOKEN, TaskEvent.Type.TOOL_RESULT, Map.of(
                "id", readCall.id(), "name", readCall.name(), "result", "read-result"));
        eventOrder.verify(fixture.transport).publish(TOKEN, TaskEvent.Type.TOOL_RESULT, Map.of(
                "id", writeCall.id(), "name", writeCall.name(), "result", "write-result"));
    }

    @Test
    void inProgressSideEffectWithoutJournalBecomesInDoubtWithoutExecution(@TempDir Path workspace) {
        ClassifiedTool sideEffect = new ClassifiedTool("side_effect", IdempotencyClass.SIDE_EFFECTFUL);
        TaskToolCatalog catalog = catalog(sideEffect);
        ToolCall call = new ToolCall("call-in-doubt", sideEffect.name(), "{}");
        Fixture fixture = fixture(workspace);
        when(fixture.stateStore.statusOf(TOKEN, call.id())).thenReturn(ToolCallStatus.IN_PROGRESS);

        fixture.coordinator.process(TOKEN, fixture.toolContext, fixture.context, catalog, List.of(call));

        verifyNoInteractions(fixture.executor);
        verify(fixture.stateStore, never()).markInProgress(any(), any());
        verify(fixture.stateStore).markInDoubt(eq(TOKEN), eq(call), any(String.class));
        InOrder eventOrder = inOrder(fixture.transport);
        eventOrder.verify(fixture.transport).publish(TOKEN, TaskEvent.Type.TOOL_CALL, Map.of(
                "id", call.id(), "name", call.name(), "arguments", call.arguments()));
        eventOrder.verify(fixture.transport).publish(eq(TOKEN), eq(TaskEvent.Type.TOOL_RESULT), any());
        Map<String, Object> toolMessage = fixture.context.messages().getLast();
        assertEquals(call.id(), toolMessage.get("tool_call_id"));
        assertTrue(String.valueOf(toolMessage.get("content")).contains("副作用是否已生效【未知】"));
    }

    @Test
    void journalReconciledSideEffectRecordsDoneWithoutExecution(@TempDir Path workspace) throws Exception {
        ClassifiedTool sideEffect = new ClassifiedTool("side_effect", IdempotencyClass.SIDE_EFFECTFUL);
        TaskToolCatalog catalog = catalog(sideEffect);
        ToolCall call = new ToolCall("call-journaled", sideEffect.name(), "{}");
        Files.createDirectories(workspace.resolve(RunJournal.REL_DIR));
        Files.writeString(RunJournal.file(workspace, call.id()), "17\n");
        Fixture fixture = fixture(workspace);
        when(fixture.stateStore.statusOf(TOKEN, call.id())).thenReturn(ToolCallStatus.IN_PROGRESS);

        fixture.coordinator.process(TOKEN, fixture.toolContext, fixture.context, catalog, List.of(call));

        verifyNoInteractions(fixture.executor);
        verify(fixture.stateStore, never()).markInProgress(any(), any());
        verify(fixture.stateStore).recordToolResult(eq(TOKEN), eq(call), any(String.class));
        InOrder eventOrder = inOrder(fixture.transport);
        eventOrder.verify(fixture.transport).publish(TOKEN, TaskEvent.Type.TOOL_CALL, Map.of(
                "id", call.id(), "name", call.name(), "arguments", call.arguments()));
        eventOrder.verify(fixture.transport).publish(eq(TOKEN), eq(TaskEvent.Type.TOOL_RESULT), any());
        String result = String.valueOf(fixture.context.messages().getLast().get("content"));
        assertTrue(result.contains("退出码 17"));
        assertTrue(result.contains("未重复执行"));
    }

    @Test
    void multipleResultsPersistInOriginalToolCallOrder(@TempDir Path workspace) {
        ClassifiedTool first = new ClassifiedTool("first", IdempotencyClass.READ_ONLY);
        ClassifiedTool second = new ClassifiedTool("second", IdempotencyClass.READ_ONLY);
        TaskToolCatalog catalog = catalog(first, second);
        ToolCall firstCall = new ToolCall("call-first", first.name(), "{}");
        ToolCall secondCall = new ToolCall("call-second", second.name(), "{}");
        List<ToolCall> calls = List.of(firstCall, secondCall);
        Fixture fixture = fixture(workspace);
        when(fixture.stateStore.statusOf(TOKEN, firstCall.id())).thenReturn(ToolCallStatus.PENDING);
        when(fixture.stateStore.statusOf(TOKEN, secondCall.id())).thenReturn(ToolCallStatus.PENDING);
        Map<String, String> reverseResultOrder = new LinkedHashMap<>();
        reverseResultOrder.put(secondCall.id(), "second-result");
        reverseResultOrder.put(firstCall.id(), "first-result");
        when(fixture.executor.executeConcurrently(same(catalog), eq(calls), same(fixture.toolContext)))
                .thenReturn(reverseResultOrder);

        fixture.coordinator.process(TOKEN, fixture.toolContext, fixture.context, catalog, calls);

        InOrder durableOrder = inOrder(fixture.stateStore);
        durableOrder.verify(fixture.stateStore).recordToolResult(TOKEN, firstCall, "first-result");
        durableOrder.verify(fixture.stateStore).recordToolResult(TOKEN, secondCall, "second-result");
        assertToolMessages(fixture.context,
                List.of(firstCall.id(), secondCall.id()),
                List.of("first-result", "second-result"));
    }

    @Test
    void doneAndInDoubtTerminalCallsAreNeverExecuted(@TempDir Path workspace) {
        ClassifiedTool read = new ClassifiedTool("read", IdempotencyClass.READ_ONLY);
        TaskToolCatalog catalog = catalog(read);
        ToolCall done = new ToolCall("call-done", read.name(), "{}");
        ToolCall inDoubt = new ToolCall("call-terminal-doubt", read.name(), "{}");
        Fixture fixture = fixture(workspace);
        when(fixture.stateStore.statusOf(TOKEN, done.id())).thenReturn(ToolCallStatus.DONE);
        when(fixture.stateStore.statusOf(TOKEN, inDoubt.id())).thenReturn(ToolCallStatus.IN_DOUBT);

        BatchDisposition disposition = fixture.coordinator.process(
                TOKEN, fixture.toolContext, fixture.context, catalog, List.of(done, inDoubt));

        assertEquals(BatchDisposition.EXECUTED, disposition);
        verifyNoInteractions(fixture.executor);
        verify(fixture.stateStore, never()).markInProgress(any(), any());
        verify(fixture.stateStore, never()).recordToolResult(any(), any(), any());
        verify(fixture.stateStore, never()).markInDoubt(any(), any(), any());
        verifyNoInteractions(fixture.transport);
        assertEquals(1, fixture.context.size());
    }

    @Test
    void inProgressFaultHookObservesCommittedLedgerBeforeToolExecution(@TempDir Path workspace) {
        ClassifiedTool read = new ClassifiedTool("read", IdempotencyClass.READ_ONLY);
        TaskToolCatalog catalog = catalog(read);
        ToolCall call = new ToolCall("call-in-progress-hook", read.name(), "{}");
        AtomicReference<ToolCallStatus> durableStatus = new AtomicReference<>(ToolCallStatus.PENDING);
        AtomicBoolean executed = new AtomicBoolean();
        AtomicReference<FaultContext> observed = new AtomicReference<>();
        FaultInjector injector = (point, context) -> {
            if (point == FaultPoint.AFTER_TOOL_MARKED_IN_PROGRESS) {
                assertEquals(ToolCallStatus.IN_PROGRESS, durableStatus.get());
                assertEquals(false, executed.get(), "hook must run before execution");
                observed.set(context);
            }
        };
        Fixture fixture = fixture(workspace, injector);
        when(fixture.stateStore.statusOf(TOKEN, call.id())).thenReturn(ToolCallStatus.PENDING);
        doAnswer(invocation -> {
            durableStatus.set(ToolCallStatus.IN_PROGRESS);
            return null;
        }).when(fixture.stateStore).markInProgress(TOKEN, call);
        when(fixture.executor.executeConcurrently(same(catalog), eq(List.of(call)), same(fixture.toolContext)))
                .thenAnswer(invocation -> {
                    executed.set(true);
                    return Map.of(call.id(), "result");
                });

        fixture.coordinator.process(TOKEN, fixture.toolContext, fixture.context, catalog, List.of(call));

        assertEquals(new FaultContext(
                        TASK_ID, TOKEN.workerId(), TOKEN.leaseEpoch(),
                        java.util.Optional.of(call.id()), java.util.Optional.empty()),
                observed.get());
    }

    @Test
    void toolResultFaultHookObservesCommittedDoneBeforeInMemoryMessage(@TempDir Path workspace) {
        ClassifiedTool read = new ClassifiedTool("read", IdempotencyClass.READ_ONLY);
        TaskToolCatalog catalog = catalog(read);
        ToolCall call = new ToolCall("call-result-hook", read.name(), "{}");
        AtomicReference<ToolCallStatus> durableStatus = new AtomicReference<>(ToolCallStatus.PENDING);
        AtomicReference<FaultContext> observed = new AtomicReference<>();
        Fixture[] holder = new Fixture[1];
        FaultInjector injector = (point, context) -> {
            if (point == FaultPoint.AFTER_TOOL_RESULT_PERSISTED_BEFORE_FINAL_ANSWER) {
                assertEquals(ToolCallStatus.DONE, durableStatus.get());
                assertEquals(1, holder[0].context.size(),
                        "the post-commit hook runs before the in-memory projection advances");
                observed.set(context);
            }
        };
        Fixture fixture = fixture(workspace, injector);
        holder[0] = fixture;
        when(fixture.stateStore.statusOf(TOKEN, call.id())).thenReturn(ToolCallStatus.PENDING);
        doAnswer(invocation -> {
            durableStatus.set(ToolCallStatus.IN_PROGRESS);
            return null;
        }).when(fixture.stateStore).markInProgress(TOKEN, call);
        when(fixture.executor.executeConcurrently(same(catalog), eq(List.of(call)), same(fixture.toolContext)))
                .thenReturn(Map.of(call.id(), "result"));
        doAnswer(invocation -> {
            durableStatus.set(ToolCallStatus.DONE);
            return null;
        }).when(fixture.stateStore).recordToolResult(TOKEN, call, "result");

        fixture.coordinator.process(TOKEN, fixture.toolContext, fixture.context, catalog, List.of(call));

        assertEquals(new FaultContext(
                        TASK_ID, TOKEN.workerId(), TOKEN.leaseEpoch(),
                        java.util.Optional.of(call.id()), java.util.Optional.empty()),
                observed.get());
        assertToolMessages(fixture.context, List.of(call.id()), List.of("result"));
    }

    private static final String TASK_ID = "task-batch";
    private static final TaskRunToken TOKEN = new TaskRunToken(TASK_ID, "worker-a", 7);

    private static Fixture fixture(Path workspace) {
        return fixture(workspace, FaultInjector.none());
    }

    private static Fixture fixture(Path workspace, FaultInjector faultInjector) {
        StateStore stateStore = mock(StateStore.class);
        ToolExecutor executor = mock(ToolExecutor.class);
        StreamTransport transport = mock(StreamTransport.class);
        ToolContext toolContext = new ToolContext(TOKEN, workspace);
        Context context = new Context("system");
        ToolBatchCoordinator coordinator = new DefaultToolBatchCoordinator(
                stateStore, executor, transport, new TaskControl(), faultInjector);
        return new Fixture(stateStore, executor, transport, toolContext, context, coordinator);
    }

    private static TaskToolCatalog catalog(ClassifiedTool... tools) {
        ToolCatalogResolver resolver = new ToolCatalogResolver(
                new ToolRegistry(Arrays.asList(tools)),
                new SchemaHasher(new ObjectMapper()),
                new ToolProperties());
        TaskProfileSnapshot snapshot = resolver.snapshot(new AgentProfileDefinition(
                "test", "v1", "system", null, null, List.of(),
                Arrays.stream(tools).map(ClassifiedTool::name).toList()));
        return resolver.resolve(snapshot);
    }

    private static void assertToolMessages(Context context, List<String> expectedIds, List<String> expectedResults) {
        List<Map<String, Object>> toolMessages = context.messages().stream()
                .filter(message -> "tool".equals(message.get("role")))
                .toList();
        assertEquals(expectedIds, toolMessages.stream()
                .map(message -> String.valueOf(message.get("tool_call_id")))
                .toList());
        assertEquals(expectedResults, toolMessages.stream()
                .map(message -> String.valueOf(message.get("content")))
                .toList());
    }

    private record Fixture(
            StateStore stateStore,
            ToolExecutor executor,
            StreamTransport transport,
            ToolContext toolContext,
            Context context,
            ToolBatchCoordinator coordinator
    ) {
    }

    private record ClassifiedTool(String name, IdempotencyClass idempotency) implements Tool {
        @Override
        public String description() {
            return "test " + name;
        }

        @Override
        public Map<String, Object> parameterSchema() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(JsonNode args, ToolContext context) {
            throw new AssertionError("The mocked ToolExecutor owns behavior in this test");
        }
    }
}
