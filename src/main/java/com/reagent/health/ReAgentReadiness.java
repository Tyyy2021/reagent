package com.reagent.health;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ReAgentReadiness(
        boolean ready,
        Instant checkedAt,
        List<ComponentReadiness> components
) {
    public ReAgentReadiness {
        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt");
        components = List.copyOf(Objects.requireNonNull(components, "components"));
    }
}
