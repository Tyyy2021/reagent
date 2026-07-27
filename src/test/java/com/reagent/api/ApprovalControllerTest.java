package com.reagent.api;

import com.reagent.approval.ApprovalConflictException;
import com.reagent.approval.ApprovalDecision;
import com.reagent.approval.ApprovalDecisionRequest;
import com.reagent.approval.ApprovalDecisionTransaction;
import com.reagent.approval.ApprovalService;
import com.reagent.approval.ApprovalStatus;
import com.reagent.approval.ApprovalView;
import com.reagent.core.AgentRunner;
import com.reagent.core.FaultContext;
import com.reagent.core.FaultPoint;
import com.reagent.core.WorkerIdentity;
import com.reagent.persist.TaskNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApprovalControllerTest {

    private ApprovalService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ApprovalService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void listsOnlySafeApprovalProjection() throws Exception {
        ApprovalView view = view(ApprovalStatus.PENDING, null);
        when(service.list("task-1")).thenReturn(List.of(view));

        mvc.perform(get("/api/tasks/task-1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taskId").value("task-1"))
                .andExpect(jsonPath("$[0].toolCallId").value("call-1"))
                .andExpect(jsonPath("$[0].assistantMessageSeq").value(2))
                .andExpect(jsonPath("$[0].toolName").value("create_ticket"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].title").value("Database unavailable"))
                .andExpect(jsonPath("$[0].severity").value("sev1"))
                .andExpect(jsonPath("$[0].evidencePreview").value("pool exhausted"))
                .andExpect(jsonPath("$[0].argumentsSnapshot").doesNotExist())
                .andExpect(jsonPath("$[0].arguments").doesNotExist())
                .andExpect(jsonPath("$[0].idempotencyKey").doesNotExist());

        verify(service).list("task-1");
    }

    @Test
    void boundedProjectionFieldsSerializeWithoutRawApprovalMaterial()
            throws Exception {
        String title = "T".repeat(255);
        String severity = "S".repeat(16);
        String evidence = "E".repeat(500) + "…[truncated]";
        ApprovalView bounded = new ApprovalView(
                "task-1",
                "call-1",
                2,
                "create_ticket",
                ApprovalStatus.PENDING,
                title,
                severity,
                evidence,
                null,
                Instant.parse("2026-07-25T00:00:00Z"),
                null);
        when(service.list("task-1")).thenReturn(List.of(bounded));

        mvc.perform(get("/api/tasks/task-1/approvals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value(title))
                .andExpect(jsonPath("$[0].severity").value(severity))
                .andExpect(jsonPath("$[0].evidencePreview").value(evidence))
                .andExpect(jsonPath("$[0].argumentsSnapshot").doesNotExist())
                .andExpect(jsonPath("$[0].arguments").doesNotExist())
                .andExpect(jsonPath("$[0].idempotencyKey").doesNotExist());
    }

    @Test
    void approveAndRejectUseExactDecisionRequestShape() throws Exception {
        ApprovalDecisionRequest approve =
                new ApprovalDecisionRequest(ApprovalDecision.APPROVE, "evidence confirmed");
        ApprovalDecisionRequest reject =
                new ApprovalDecisionRequest(ApprovalDecision.REJECT, "insufficient evidence");
        when(service.decide("task-1", "call-1", approve))
                .thenReturn(view(ApprovalStatus.APPROVED, approve.reason()));
        when(service.decide("task-1", "call-2", reject))
                .thenReturn(new ApprovalView(
                        "task-1",
                        "call-2",
                        2,
                        "create_ticket",
                        ApprovalStatus.REJECTED,
                        "Database unavailable",
                        "sev1",
                        "pool exhausted",
                        reject.reason(),
                        Instant.parse("2026-07-25T00:00:00Z"),
                        Instant.parse("2026-07-25T00:01:00Z")));

        mvc.perform(post("/api/tasks/task-1/approvals/call-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVE","reason":"evidence confirmed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionReason").value("evidence confirmed"));
        mvc.perform(post("/api/tasks/task-1/approvals/call-2/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECT","reason":"insufficient evidence"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value("insufficient evidence"));

        verify(service).decide("task-1", "call-1", approve);
        verify(service).decide("task-1", "call-2", reject);
    }

    @Test
    void validationRejectsMissingDecisionAndOversizedReason() throws Exception {
        mvc.perform(post("/api/tasks/task-1/approvals/call-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"missing decision\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/task-1/approvals/call-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reason\":\""
                                + "x".repeat(513) + "\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void conflictsMapTo409AndCrossTaskIdentityMapsTo404() throws Exception {
        ApprovalDecisionRequest approve =
                new ApprovalDecisionRequest(ApprovalDecision.APPROVE, null);
        when(service.decide("task-1", "call-conflict", approve))
                .thenThrow(new ApprovalConflictException("approval decision conflicts"));
        when(service.decide("task-other", "call-1", approve))
                .thenThrow(new TaskNotFoundException("task-other"));

        mvc.perform(post("/api/tasks/task-1/approvals/call-conflict/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("approval decision conflicts"));
        mvc.perform(post("/api/tasks/task-other/approvals/call-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameDecisionHttpReplayReturnsViewAndOppositeDecisionReturnsConflict()
            throws Exception {
        ApprovalDecisionRequest approve =
                new ApprovalDecisionRequest(ApprovalDecision.APPROVE, "confirmed");
        ApprovalDecisionRequest reject =
                new ApprovalDecisionRequest(ApprovalDecision.REJECT, "changed");
        ApprovalView approved = view(ApprovalStatus.APPROVED, "confirmed");
        when(service.decide("task-1", "call-1", approve))
                .thenReturn(approved);
        when(service.decide("task-1", "call-1", reject))
                .thenThrow(new ApprovalConflictException(
                        "Approval was already decided differently"));

        for (int replay = 0; replay < 2; replay++) {
            mvc.perform(post("/api/tasks/task-1/approvals/call-1/decision")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"decision":"APPROVE","reason":"confirmed"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(jsonPath("$.argumentsSnapshot").doesNotExist())
                    .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
        }
        mvc.perform(post("/api/tasks/task-1/approvals/call-1/decision")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECT","reason":"changed"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "Approval was already decided differently"));
    }

    @Test
    void serviceHitsPostCommitHookBeforeResumeAndNeverResumesAReplay() {
        ApprovalDecisionTransaction transaction =
                mock(ApprovalDecisionTransaction.class);
        AgentRunner runner = mock(AgentRunner.class);
        AtomicBoolean transactionReturned = new AtomicBoolean();
        AtomicBoolean hookHit = new AtomicBoolean();
        AtomicReference<FaultContext> faultContext = new AtomicReference<>();
        ApprovalView approved = view(ApprovalStatus.APPROVED, "confirmed");
        ApprovalDecisionRequest request =
                new ApprovalDecisionRequest(ApprovalDecision.APPROVE, "confirmed");
        when(transaction.decide("task-1", "call-1", request))
                .thenAnswer(invocation -> {
                    transactionReturned.set(true);
                    return new ApprovalDecisionTransaction.DecisionOutcome(
                            approved, true, 9);
                })
                .thenReturn(new ApprovalDecisionTransaction.DecisionOutcome(
                        approved, false, 9));
        doAnswer(invocation -> {
            assertTrue(hookHit.get(), "resume must happen after the fault boundary");
            return null;
        }).when(runner).resumeAsync("task-1");
        ApprovalService concrete = new ApprovalService(
                transaction,
                runner,
                (point, context) -> {
                    assertEquals(
                            FaultPoint.AFTER_APPROVAL_DECIDED_BEFORE_RESUME,
                            point);
                    assertTrue(
                            transactionReturned.get(),
                            "fault boundary must run after transaction return");
                    hookHit.set(true);
                    faultContext.set(context);
                },
                new WorkerIdentity("approval-api", "0"));

        ApprovalView first = concrete.decide("task-1", "call-1", request);
        hookHit.set(false);
        ApprovalView replay = concrete.decide("task-1", "call-1", request);

        assertEquals(approved, first);
        assertEquals(approved, replay);
        assertFalse(hookHit.get(), "same-decision replay must not hit resume boundary");
        assertEquals(new FaultContext(
                        "task-1",
                        "approval-api",
                        9,
                        Optional.of("call-1"),
                        Optional.of(2)),
                faultContext.get());
        verify(runner).resumeAsync("task-1");
    }

    private static ApprovalView view(
            ApprovalStatus status,
            String reason
    ) {
        return new ApprovalView(
                "task-1",
                "call-1",
                2,
                "create_ticket",
                status,
                "Database unavailable",
                "sev1",
                "pool exhausted",
                reason,
                Instant.parse("2026-07-25T00:00:00Z"),
                status == ApprovalStatus.PENDING
                        ? null
                        : Instant.parse("2026-07-25T00:01:00Z"));
    }
}
