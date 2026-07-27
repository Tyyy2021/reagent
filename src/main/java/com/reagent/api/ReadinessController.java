package com.reagent.api;

import com.reagent.health.ReAgentReadiness;
import com.reagent.health.ReAgentReadinessHealthIndicator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/readiness")
public final class ReadinessController {

    private final ReAgentReadinessHealthIndicator readiness;

    public ReadinessController(ReAgentReadinessHealthIndicator readiness) {
        this.readiness = readiness;
    }

    @GetMapping
    public ReAgentReadiness readiness() {
        return readiness.readiness();
    }
}
