package com.reagent.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record IncidentRequest(
        @NotBlank @Size(max = 64) String source,
        @NotBlank @Size(max = 128) String externalAlertId,
        @NotBlank @Size(max = 64) String service,
        @NotBlank @Size(max = 16) String severity,
        @NotBlank @Size(max = 256) String title,
        @NotBlank @Size(max = 2048) String summary,
        @NotNull Instant startedAt,
        @NotNull @Size(max = 20)
        Map<@NotBlank @Size(max = 64) String, @NotBlank @Size(max = 256) String> labels
) {
    public IncidentRequest {
        labels = Map.copyOf(Objects.requireNonNull(labels, "labels"));
    }
}
