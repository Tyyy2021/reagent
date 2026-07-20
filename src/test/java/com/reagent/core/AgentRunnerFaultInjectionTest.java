package com.reagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reagent.llm.LlmClient;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.profile.AgentProfileDefinition;
import com.reagent.profile.AgentProfileRegistry;
import com.reagent.profile.SchemaHasher;
import com.reagent.profile.TaskProfileSnapshot;
import com.reagent.profile.TaskToolCatalog;
import com.reagent.profile.ToolCatalogResolver;
import com.reagent.sandbox.WorkspaceStore;
import com.reagent.stream.StreamTransport;
import com.reagent.stream.TaskEvent;
import com.reagent.tool.Tool;
import com.reagent.tool.ToolContext;
import com.reagent.tool.ToolProperties;
import com.reagent.tool.ToolRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRunnerFaultInjectionTest {

    @Test
    void toolBatchAssistantHookRunsAfterCommitAndCrashEscapesWithoutFailedStateOrEvent(@TempDir Path workspace) {
        Fixture fixture = fixture(workspace, toolDecision());
        AtomicReference<FaultContext> observed = new AtomicReference<>();
        FaultInjector injector = (point, context) -> {
            if (point == FaultPoint.AFTER_ASSISTANT_PERSISTED_BEFORE_APPROVAL) {
                assertTrue(fixture.assistantPersisted.get());
                assertFalse(fixture.context.messages().stream()
                        .anyMatch(message -> "assistant".equals(message.get("role"))),
                        "the durable commit hook must precede the in-memory projection");
                observed.set(context);
                throw new InjectedWorkerCrashException(point, context);
            }
        };
        AgentRunner runner = fixture.runner(injector);

        InjectedWorkerCrashException crash = assertThrows(
                InjectedWorkerCrashException.class,
                () -> runner.run("goal", "coding"));

        FaultContext expected = new FaultContext(
                fixture.token.taskId(), fixture.token.workerId(), fixture.token.leaseEpoch(),
                Optional.empty(), Optional.of(23));
        assertEquals(expected, observed.get());
        assertEquals(expected, crash.faultContext());
        verify(fixture.stateStore, never()).failTask(any(), any());
        verify(fixture.transport, never()).publish(
                any(TaskRunToken.class), eq(TaskEvent.Type.FAILED), any());
        verifyNoInteractions(fixture.coordinator);
    }

    @Test
    void finalAnswerAssistantDoesNotTriggerBatchFaultPoint(@TempDir Path workspace) {
        Fixture fixture = fixture(workspace, Decision.finalAnswer(
                "done", Map.of("role", "assistant", "content", "done")));
        AtomicBoolean hit = new AtomicBoolean();
        AgentRunner runner = fixture.runner((point, context) -> hit.set(true));

        AgentRunner.RunResult result = runner.run("goal", "coding");

        assertEquals("done", result.result());
        assertFalse(hit.get());
        verify(fixture.stateStore).completeTask(fixture.token, "done");
    }

    @Test
    void delegatesToolEventPublicationToCoordinator(@TempDir Path workspace) {
        Fixture fixture = fixture(workspace, toolDecision());
        when(fixture.coordinator.process(
                same(fixture.token), any(), same(fixture.context), same(fixture.catalog), any()))
                .thenReturn(BatchDisposition.WAITING_APPROVAL);
        AgentRunner runner = fixture.runner(FaultInjector.none());

        AgentRunner.RunResult result = runner.run("goal", "coding");

        assertEquals("任务正在等待审批。", result.result());
        verify(fixture.coordinator).process(
                same(fixture.token), any(), same(fixture.context), same(fixture.catalog), any());
        verify(fixture.transport, never()).publish(
                same(fixture.token), eq(TaskEvent.Type.TOOL_CALL), any());
    }

    private static Decision toolDecision() {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", null);
        assistant.put("tool_calls", List.of(Map.of(
                "id", "call-1",
                "type", "function",
                "function", Map.of("name", "allowed", "arguments", "{}"))));
        return Decision.tools(assistant, List.of(new ToolCall("call-1", "allowed", "{}")));
    }

    private static Fixture fixture(Path workspace, Decision decision) {
        LlmClient llm = mock(LlmClient.class);
        AgentProfileRegistry profiles = mock(AgentProfileRegistry.class);
        ToolCatalogResolver resolver = mock(ToolCatalogResolver.class);
        ToolBatchCoordinator coordinator = mock(ToolBatchCoordinator.class);
        StateStore stateStore = mock(StateStore.class);
        StreamTransport transport = mock(StreamTransport.class);
        Tool allowed = new Tool() {
            @Override public String name() { return "allowed"; }
            @Override public String description() { return "allowed test tool"; }
            @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
            @Override public String execute(JsonNode args, ToolContext context) { return "ok"; }
        };
        ToolCatalogResolver actualResolver = new ToolCatalogResolver(
                new ToolRegistry(List.of(allowed)), new SchemaHasher(new ObjectMapper()), new ToolProperties());
        TaskProfileSnapshot snapshot = actualResolver.snapshot(new AgentProfileDefinition(
                "coding", "v1", "system", null, null, List.of(), List.of("allowed")));
        TaskToolCatalog catalog = actualResolver.resolve(snapshot);
        TaskEntity task = TaskEntity.newTask("goal", Instant.EPOCH);
        TaskRunToken token = new TaskRunToken(task.getId(), "worker-a", 7);
        Context context = new Context("system");
        context.addUser("goal");
        AtomicBoolean assistantPersisted = new AtomicBoolean();

        when(profiles.snapshot("coding")).thenReturn(snapshot);
        when(resolver.resolve(snapshot)).thenReturn(catalog);
        when(stateStore.createTask("goal", snapshot)).thenReturn(task);
        when(stateStore.claim(task.getId())).thenReturn(Optional.of(token));
        when(stateStore.loadContext(task.getId())).thenReturn(context);
        when(stateStore.readControlSignal(task.getId())).thenReturn("NONE");
        when(stateStore.getTask(task.getId())).thenReturn(task);
        when(stateStore.appendAssistant(same(token), any())).thenAnswer(invocation -> {
            assistantPersisted.set(true);
            return 23;
        });
        when(llm.chatStream(same(context), eq(catalog.toOpenAiSpec()), any())).thenReturn(decision);
        when(coordinator.process(same(token), any(), same(context), same(catalog), any()))
                .thenReturn(BatchDisposition.EXECUTED);

        return new Fixture(
                workspace, llm, profiles, resolver, coordinator, stateStore, transport,
                snapshot, catalog, task, token, context, assistantPersisted);
    }

    private record Fixture(
            Path workspace,
            LlmClient llm,
            AgentProfileRegistry profiles,
            ToolCatalogResolver resolver,
            ToolBatchCoordinator coordinator,
            StateStore stateStore,
            StreamTransport transport,
            TaskProfileSnapshot snapshot,
            TaskToolCatalog catalog,
            TaskEntity task,
            TaskRunToken token,
            Context context,
            AtomicBoolean assistantPersisted
    ) {
        AgentRunner runner(FaultInjector faultInjector) {
            WorkspaceStore workspaces = new WorkspaceStore() {
                @Override
                public Path checkout(String taskId) {
                    return workspace;
                }

                @Override
                public void commit(String taskId) {
                }
            };
            return new AgentRunner(
                    llm, profiles, resolver, coordinator, faultInjector,
                    stateStore, new ShutdownState(), workspaces, new InFlightTasks(), transport,
                    new TaskControl(), OpenTelemetry.noop().getTracer("agent-runner-fault-test"),
                    new WorkerIdentity("worker-a", "0"), 3);
        }
    }
}
