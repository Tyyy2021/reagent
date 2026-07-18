CREATE TABLE task (
    id VARCHAR(255) NOT NULL,
    goal MEDIUMTEXT NULL,
    status VARCHAR(32) NOT NULL,
    result MEDIUMTEXT NULL,
    created_at DATETIME(6) NULL,
    updated_at DATETIME(6) NULL,
    recovery_count INT NOT NULL DEFAULT 0,
    owner_id VARCHAR(64) NULL,
    lease_expires_at DATETIME(6) NULL,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    control_signal VARCHAR(16) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(255) NULL,
    seq INT NOT NULL,
    role VARCHAR(255) NULL,
    content MEDIUMTEXT NULL,
    tool_calls_json MEDIUMTEXT NULL,
    tool_call_id VARCHAR(255) NULL,
    created_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_msg_task_seq UNIQUE (task_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tool_call (
    id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NULL,
    tool_name VARCHAR(255) NULL,
    arguments MEDIUMTEXT NULL,
    result MEDIUMTEXT NULL,
    status VARCHAR(32) NULL,
    created_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(255) NULL,
    type VARCHAR(32) NULL,
    data MEDIUMTEXT NULL,
    created_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_event_task_id (task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
