package com.reagent.incident;

public record IncidentAccepted(String incidentId, String taskId, boolean deduplicated) {
}
