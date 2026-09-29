CREATE TABLE tool_execution_outcomes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(120) NOT NULL,
    result_payload LONGTEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    schema_version INT NOT NULL,
    CONSTRAINT pk_tool_execution_outcomes PRIMARY KEY (id),
    CONSTRAINT uk_tool_execution_outcomes_execution UNIQUE (execution_id),
    CONSTRAINT uk_tool_execution_outcomes_run_call UNIQUE (run_id, tool_call_id),
    CONSTRAINT fk_tool_execution_outcomes_execution
        FOREIGN KEY (execution_id) REFERENCES tool_executions (execution_id),
    CONSTRAINT fk_tool_execution_outcomes_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (run_id)
);
