CREATE TABLE tool_reconciliation_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    reconciliation_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    tool_execution_id VARCHAR(64) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    attempt_no BIGINT NOT NULL,
    contract_id VARCHAR(96) NOT NULL,
    strategy_id VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    outcome VARCHAR(40),
    external_reference VARCHAR(500),
    evidence_summary VARCHAR(2000),
    error_code VARCHAR(80),
    error_message VARCHAR(1000),
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_tool_reconciliation_attempts PRIMARY KEY (id),
    CONSTRAINT uk_tool_reconciliation_attempts_identity UNIQUE (reconciliation_id),
    CONSTRAINT uk_tool_reconciliation_attempts_sequence
        UNIQUE (tool_execution_id, attempt_no),
    CONSTRAINT fk_tool_reconciliation_attempts_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (run_id),
    CONSTRAINT fk_tool_reconciliation_attempts_execution
        FOREIGN KEY (tool_execution_id) REFERENCES tool_executions (execution_id)
);

CREATE INDEX idx_tool_reconciliation_attempts_run_call
    ON tool_reconciliation_attempts (run_id, tool_call_id);

CREATE INDEX idx_tool_reconciliation_attempts_status
    ON tool_reconciliation_attempts (status);
