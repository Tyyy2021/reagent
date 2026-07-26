package com.reagent.api;

import java.time.Instant;

public record IncidentSummaryView(
        String source,
        String externalAlertId,
        String service,
        String severity,
        Instant startedAt
) {
}
