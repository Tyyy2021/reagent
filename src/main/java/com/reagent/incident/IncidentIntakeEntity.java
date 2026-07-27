package com.reagent.incident;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "incident_intake",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_incident_source_external",
                        columnNames = {"source", "external_alert_id"}),
                @UniqueConstraint(name = "uk_incident_task", columnNames = "task_id")
        }
)
public class IncidentIntakeEntity {

    private static final int MAX_PAYLOAD_BYTES = 32 * 1024;

    @Id
    @Column(nullable = false, length = 36)
    private String id;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "external_alert_id", nullable = false, length = 128)
    private String externalAlertId;

    @Column(name = "bounded_payload_json", nullable = false, length = 1_000_000)
    private String boundedPayloadJson;

    @Column(name = "task_id", nullable = false, length = 255)
    private String taskId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IncidentIntakeEntity() {
    }

    public static IncidentIntakeEntity create(
            String id,
            IncidentRequest incident,
            String taskId,
            Instant createdAt,
            ObjectMapper mapper
    ) {
        Objects.requireNonNull(incident, "incident");
        String payload = serialize(incident, mapper);
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Incident payload exceeds the persisted bound");
        }

        IncidentIntakeEntity entity = new IncidentIntakeEntity();
        entity.id = Objects.requireNonNull(id, "id");
        entity.source = incident.source();
        entity.externalAlertId = incident.externalAlertId();
        entity.boundedPayloadJson = payload;
        entity.taskId = Objects.requireNonNull(taskId, "taskId");
        entity.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return entity;
    }

    private static String serialize(IncidentRequest incident, ObjectMapper mapper) {
        try {
            return Objects.requireNonNull(mapper, "mapper").writeValueAsString(incident);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Incident payload cannot be serialized", exception);
        }
    }

    public String getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getExternalAlertId() {
        return externalAlertId;
    }

    public String getBoundedPayloadJson() {
        return boundedPayloadJson;
    }

    public String getTaskId() {
        return taskId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
