ALTER TABLE agent_runs
    ADD COLUMN user_id BIGINT NOT NULL;

ALTER TABLE agent_runs
    ADD COLUMN current_iteration INTEGER NOT NULL DEFAULT 0;

ALTER TABLE agent_runs
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_agent_runs_user_id ON agent_runs (user_id);

ALTER TABLE agent_steps
    ADD COLUMN step_index INTEGER;

UPDATE agent_steps AS target_step
SET step_index = (
    SELECT ranked_step.sequence_number
    FROM (
        SELECT
            id,
            ROW_NUMBER() OVER (
                PARTITION BY run_id
                ORDER BY iteration, id
            ) AS sequence_number
        FROM agent_steps
    ) AS ranked_step
    WHERE ranked_step.id = target_step.id
);

ALTER TABLE agent_steps
    MODIFY COLUMN step_index INTEGER NOT NULL;

ALTER TABLE agent_steps
    ADD CONSTRAINT uk_agent_steps_run_step_index UNIQUE (run_id, step_index);

ALTER TABLE tool_executions
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
