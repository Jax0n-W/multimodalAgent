ALTER TABLE agent_runtime_config_snapshots
    MODIFY COLUMN config_json LONGTEXT NOT NULL;

ALTER TABLE agent_recovery_checkpoints
    MODIFY COLUMN state_json LONGTEXT NOT NULL;

ALTER TABLE tool_execution_outcomes
    MODIFY COLUMN result_payload LONGTEXT NOT NULL;

ALTER TABLE agent_context_snapshots
    MODIFY COLUMN context_json LONGTEXT NOT NULL;
