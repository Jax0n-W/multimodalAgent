CREATE TABLE agent_runtime_config_snapshots (
    snapshot_id VARCHAR(96) NOT NULL,
    schema_version INTEGER NOT NULL,
    config_hash VARCHAR(64) NOT NULL,
    config_json TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_runtime_config_snapshots PRIMARY KEY (snapshot_id),
    CONSTRAINT uk_agent_runtime_config_snapshots_hash UNIQUE (config_hash)
);

ALTER TABLE agent_runs
    ADD COLUMN runtime_config_snapshot_id VARCHAR(96);

ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_runs_runtime_config_snapshot
        FOREIGN KEY (runtime_config_snapshot_id)
        REFERENCES agent_runtime_config_snapshots (snapshot_id);

CREATE INDEX idx_agent_runs_runtime_config_snapshot_id
    ON agent_runs (runtime_config_snapshot_id);
