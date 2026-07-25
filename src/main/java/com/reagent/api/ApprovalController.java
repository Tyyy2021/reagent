package com.reagent.api;

import com.reagent.approval.ApprovalDecisionRequest;
import com.reagent.approval.ApprovalService;
import com.reagent.approval.ApprovalView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks/{taskId}/approvals")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApprovalView> list(@PathVariable String taskId) {
        return service.list(taskId);
    }

    @PostMapping("/{toolCallId}/decision")
    public ApprovalView decide(
            @PathVariable String taskId,
            @PathVariable String toolCallId,
            @Valid @RequestBody ApprovalDecisionRequest request
    ) {
        return service.decide(taskId, toolCallId, request);
    }
}
