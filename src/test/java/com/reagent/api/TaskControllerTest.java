package com.reagent.api;

import com.reagent.approval.ApprovalService;
import com.reagent.core.AgentRunner;
import com.reagent.core.TaskControl;
import com.reagent.persist.StateStore;
import com.reagent.persist.TaskEntity;
import com.reagent.persist.TaskNotFoundException;
import com.reagent.persist.TaskStatus;
import com.reagent.profile.UnknownProfileException;
import com.reagent.stream.StreamTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TaskControllerTest {

    private AgentRunner runner;
    private StateStore stateStore;
    private TaskControl taskControl;
    private ApprovalService approvalService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runner = mock(AgentRunner.class);
        stateStore = mock(StateStore.class);
        taskControl = mock(TaskControl.class);
        approvalService = mock(ApprovalService.class);
        TaskController controller = new TaskController(
                runner,
                stateStore,
                mock(StreamTransport.class),
                taskControl,
                approvalService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void omittedProfileInvokesCodingWithoutChangingAsyncResponse() throws Exception {
        when(runner.submit("inspect", "coding")).thenReturn("task-1");

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"inspect\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));

        verify(runner).submit("inspect", "coding");
        verifyNoInteractions(stateStore);
    }

    @Test
    void explicitProfilePreservesSynchronousContract() throws Exception {
        when(runner.run("inspect", "coding"))
                .thenReturn(new AgentRunner.RunResult("task-2", "done"));

        mvc.perform(post("/api/tasks?sync=true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"inspect\",\"profile\":\"coding\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-2"))
                .andExpect(jsonPath("$.goal").value("inspect"))
                .andExpect(jsonPath("$.result").value("done"));

        verify(runner).run("inspect", "coding");
    }

    @Test
    void explicitUnknownProfileReturnsClientErrorBeforeStateStoreUse() throws Exception {
        when(runner.submit("inspect", "incident-ops"))
                .thenThrow(new UnknownProfileException("incident-ops"));

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"inspect\",\"profile\":\"incident-ops\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown agent profile: incident-ops"));

        verifyNoInteractions(stateStore);
    }

    @Test
    void statusAddsPersistedProfileWithoutChangingExistingFields() throws Exception {
        TaskEntity task = mock(TaskEntity.class);
        when(task.getId()).thenReturn("task-3");
        when(task.getStatus()).thenReturn(TaskStatus.RUNNING);
        when(task.getGoal()).thenReturn("inspect");
        when(task.getResult()).thenReturn(null);
        when(task.getProfileId()).thenReturn("coding");
        when(stateStore.getTask("task-3")).thenReturn(task);

        mvc.perform(get("/api/tasks/task-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-3"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.goal").value("inspect"))
                .andExpect(jsonPath("$.result").value(""))
                .andExpect(jsonPath("$.profile").value("coding"));
    }

    @Test
    void missingTaskReturnsNotFound() throws Exception {
        when(stateStore.getTask("missing")).thenThrow(new TaskNotFoundException("missing"));

        mvc.perform(get("/api/tasks/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Task not found: missing"));
    }

    @Test
    void cancellingMissingTaskReturnsNotFound() throws Exception {
        when(stateStore.getTask("missing-cancel"))
                .thenThrow(new TaskNotFoundException("missing-cancel"));

        mvc.perform(post("/api/tasks/missing-cancel/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Task not found: missing-cancel"));
    }

    @Test
    void pausingMissingTaskReturnsNotFound() throws Exception {
        when(stateStore.getTask("missing-pause"))
                .thenThrow(new TaskNotFoundException("missing-pause"));

        mvc.perform(post("/api/tasks/missing-pause/pause"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Task not found: missing-pause"));
    }

    @Test
    void cancellingExistingNonRunningTaskRemainsConflict() throws Exception {
        when(stateStore.getTask("completed-cancel")).thenReturn(mock(TaskEntity.class));

        mvc.perform(post("/api/tasks/completed-cancel/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.taskId").value("completed-cancel"));
    }

    @Test
    void cancellingWaitingApprovalTaskIsAcceptedWithoutSchedulingResume() throws Exception {
        when(approvalService.cancelWaiting("waiting-cancel")).thenReturn(true);

        mvc.perform(post("/api/tasks/waiting-cancel/cancel"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("waiting-cancel"));

        verify(approvalService).cancelWaiting("waiting-cancel");
        verifyNoInteractions(runner);
        verifyNoInteractions(stateStore);
        verifyNoInteractions(taskControl);
    }

    @Test
    void localCancelRechecksWaitingStateBeforeReturningAccepted() throws Exception {
        when(approvalService.cancelWaiting("local-transition"))
                .thenReturn(false, true);
        when(taskControl.requestCancel("local-transition", false))
                .thenReturn(true);

        mvc.perform(post("/api/tasks/local-transition/cancel"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("local-transition"))
                .andExpect(jsonPath("$.message")
                        .value("已取消等待审批的任务"));

        verify(approvalService, times(2))
                .cancelWaiting("local-transition");
        verify(taskControl).requestCancel("local-transition", false);
        verifyNoInteractions(stateStore);
    }

    @Test
    void crossWorkerCancelRechecksWaitingStateWhenRunningUpdateLosesTransition()
            throws Exception {
        when(approvalService.cancelWaiting("remote-transition"))
                .thenReturn(false, true);
        when(stateStore.requestControl("remote-transition", "CANCEL"))
                .thenReturn(false);

        mvc.perform(post("/api/tasks/remote-transition/cancel"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("remote-transition"))
                .andExpect(jsonPath("$.message")
                        .value("已取消等待审批的任务"));

        verify(approvalService, times(2))
                .cancelWaiting("remote-transition");
        verify(taskControl).requestCancel("remote-transition", false);
        verify(stateStore).requestControl("remote-transition", "CANCEL");
        verify(stateStore, never()).getTask("remote-transition");
    }

    @Test
    void pausingExistingNonRunningTaskRemainsConflict() throws Exception {
        when(stateStore.getTask("completed-pause")).thenReturn(mock(TaskEntity.class));

        mvc.perform(post("/api/tasks/completed-pause/pause"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.taskId").value("completed-pause"));
    }
}
