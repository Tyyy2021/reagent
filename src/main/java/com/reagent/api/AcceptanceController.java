package com.reagent.api;

import com.reagent.acceptance.AcceptanceEvidence;
import com.reagent.acceptance.AcceptanceService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile({"demo-smoke", "demo-chaos", "test"})
@RequestMapping("/api/acceptance/tasks")
public final class AcceptanceController {

    private final AcceptanceService acceptance;

    public AcceptanceController(AcceptanceService acceptance) {
        this.acceptance = acceptance;
    }

    @GetMapping("/{taskId}")
    public AcceptanceEvidence evidence(@PathVariable String taskId) {
        return acceptance.evidence(taskId);
    }
}
