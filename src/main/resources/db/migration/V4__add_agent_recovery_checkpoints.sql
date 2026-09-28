CREATE TABLE agent_recovery_checkpoints (
    id BIGINT NOT NULL AUTO_INCREMENT,
    checkpoint_id VARCHAR(96) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    checkpoint_sequence BIGINT NOT NULL,
    schema_version INTEGER NOT NULL,
    iteration INTEGER NOT NULL,
    boundary VARCHAR(40) NOT NULL,
    state_json LONGTEXT NOT NULL,
    runtime_config_snapshot_id VARCHAR(96) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_recovery_checkpoints PRIMARY KEY (id),
    CONSTRAINT uk_agent_recovery_checkpoints_identity UNIQUE (checkpoint_id),
    CONSTRAINT uk_agent_recovery_checkpoints_run_sequence UNIQUE (run_id, checkpoint_sequence),
    CONSTRAINT fk_agent_recovery_checkpoints_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (run_id),
    CONSTRAINT fk_agent_recovery_checkpoints_runtime_config_snapshot
        FOREIGN KEY (runtime_config_snapshot_id)
        REFERENCES agent_runtime_config_snapshots (snapshot_id)
);

CREATE INDEX idx_agent_recovery_checkpoints_run_created
    ON agent_recovery_checkpoints (run_id, created_at);
