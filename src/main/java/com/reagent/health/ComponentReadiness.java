package com.reagent.health;

import java.util.Objects;

public record ComponentReadiness(
        String name,
        boolean ready,
        String version,
        String reason
) {
    public ComponentReadiness {
        name = Objects.requireNonNull(name, "name");
        version = Objects.requireNonNull(version, "version");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
