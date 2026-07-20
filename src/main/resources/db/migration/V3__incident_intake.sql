CREATE TABLE incident_intake (
    id VARCHAR(36) NOT NULL,
    source VARCHAR(64) NOT NULL,
    external_alert_id VARCHAR(128) NOT NULL,
    bounded_payload_json MEDIUMTEXT NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_incident_source_external UNIQUE (source, external_alert_id),
    CONSTRAINT uk_incident_task UNIQUE (task_id),
    CONSTRAINT fk_incident_task FOREIGN KEY (task_id) REFERENCES task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
