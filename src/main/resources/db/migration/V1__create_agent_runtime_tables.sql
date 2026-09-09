CREATE TABLE agent_runs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    model_version VARCHAR(120),
    prompt_version VARCHAR(120),
    skill_version VARCHAR(120),
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    final_content VARCHAR(12000),
    stop_reason VARCHAR(40),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_runs PRIMARY KEY (id),
    CONSTRAINT uk_agent_runs_run_id UNIQUE (run_id),
    CONSTRAINT uk_agent_runs_request_id UNIQUE (request_id)
);

CREATE INDEX idx_agent_runs_session_id ON agent_runs (session_id);
CREATE INDEX idx_agent_runs_status ON agent_runs (status);

CREATE TABLE agent_steps (
    id BIGINT NOT NULL AUTO_INCREMENT,
    step_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    iteration INTEGER NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    error_code VARCHAR(80),
    error_message VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_agent_steps PRIMARY KEY (id),
    CONSTRAINT uk_agent_steps_step_id UNIQUE (step_id),
    CONSTRAINT fk_agent_steps_run_id FOREIGN KEY (run_id) REFERENCES agent_runs (run_id)
);

CREATE INDEX idx_agent_steps_run_iteration ON agent_steps (run_id, iteration);

CREATE TABLE tool_executions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    execution_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    step_id VARCHAR(64) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    tool_name VARCHAR(120) NOT NULL,
    arguments_hash VARCHAR(128),
    idempotency_key VARCHAR(160),
    status VARCHAR(32) NOT NULL,
    result_summary VARCHAR(2000),
    error_code VARCHAR(80),
    error_message VARCHAR(1000),
    started_at TIMESTAMP(6),
    completed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_tool_executions PRIMARY KEY (id),
    CONSTRAINT uk_tool_executions_execution_id UNIQUE (execution_id),
    CONSTRAINT uk_tool_executions_run_call UNIQUE (run_id, tool_call_id),
    CONSTRAINT uk_tool_executions_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_tool_executions_run_id FOREIGN KEY (run_id) REFERENCES agent_runs (run_id),
    CONSTRAINT fk_tool_executions_step_id FOREIGN KEY (step_id) REFERENCES agent_steps (step_id)
);

CREATE INDEX idx_tool_executions_run_id ON tool_executions (run_id);
CREATE INDEX idx_tool_executions_step_id ON tool_executions (step_id);
CREATE INDEX idx_tool_executions_tool_call_id ON tool_executions (tool_call_id);
CREATE INDEX idx_tool_executions_status ON tool_executions (status);
