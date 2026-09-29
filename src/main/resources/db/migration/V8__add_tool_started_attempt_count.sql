ALTER TABLE tool_executions
    ADD COLUMN started_attempt_count BIGINT NOT NULL DEFAULT 0;

UPDATE tool_executions
SET started_attempt_count = 1
WHERE status IN ('STARTED', 'UNKNOWN', 'SUCCEEDED', 'FAILED')
  AND started_attempt_count < 1;
