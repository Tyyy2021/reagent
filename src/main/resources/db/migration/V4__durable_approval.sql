CREATE TABLE approval_request (
    tool_call_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    assistant_message_seq INT NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    arguments_snapshot MEDIUMTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    decision_reason VARCHAR(512) NULL,
    requested_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NULL,
    PRIMARY KEY (tool_call_id),
    INDEX idx_approval_task_status (task_id, status),
    CONSTRAINT fk_approval_task FOREIGN KEY (task_id) REFERENCES task(id),
    CONSTRAINT fk_approval_tool_call FOREIGN KEY (tool_call_id) REFERENCES tool_call(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
