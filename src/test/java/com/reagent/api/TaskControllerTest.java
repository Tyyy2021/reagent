package com.reagent.api;

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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        runner = mock(AgentRunner.class);
        stateStore = mock(StateStore.class);
        TaskController controller = new TaskController(
                runner, stateStore, mock(StreamTransport.class), mock(TaskControl.class));
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
}
