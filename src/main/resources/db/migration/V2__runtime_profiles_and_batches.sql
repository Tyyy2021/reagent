ALTER TABLE task
    ADD COLUMN profile_id VARCHAR(64) NULL,
    ADD COLUMN profile_snapshot MEDIUMTEXT NULL,
    MODIFY COLUMN status VARCHAR(32) NOT NULL;

UPDATE task
SET profile_id = 'coding'
WHERE profile_id IS NULL;

ALTER TABLE tool_call
    ADD COLUMN assistant_message_seq INT NULL,
    MODIFY COLUMN status VARCHAR(32) NULL;

CREATE INDEX idx_tool_call_task_batch
    ON tool_call (task_id, assistant_message_seq);
