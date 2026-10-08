CREATE TABLE agent_context_snapshots (
    snapshot_id VARCHAR(96) NOT NULL,
    schema_version INTEGER NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    context_hash CHAR(64) NOT NULL,
    context_json LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_context_snapshots PRIMARY KEY (snapshot_id),
    CONSTRAINT uk_agent_context_snapshots_hash UNIQUE (context_hash)
);

ALTER TABLE agent_runs
    ADD COLUMN context_snapshot_id VARCHAR(96);

ALTER TABLE agent_runs
    ADD CONSTRAINT fk_agent_runs_context_snapshot
        FOREIGN KEY (context_snapshot_id)
        REFERENCES agent_context_snapshots (snapshot_id);

CREATE INDEX idx_agent_runs_context_snapshot_id
    ON agent_runs (context_snapshot_id);
