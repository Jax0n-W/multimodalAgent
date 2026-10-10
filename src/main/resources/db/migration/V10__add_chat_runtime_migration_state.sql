CREATE TABLE chat_runtime_sessions (
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_chat_runtime_sessions PRIMARY KEY (session_id)
);

CREATE INDEX idx_chat_runtime_sessions_user_id
    ON chat_runtime_sessions (user_id);

CREATE TABLE chat_history_projections (
    run_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    chat_message_id BIGINT,
    error_message VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_chat_history_projections PRIMARY KEY (run_id),
    CONSTRAINT fk_chat_history_projection_run
        FOREIGN KEY (run_id) REFERENCES agent_runs (run_id),
    CONSTRAINT fk_chat_history_projection_session
        FOREIGN KEY (session_id) REFERENCES chat_runtime_sessions (session_id)
);

CREATE INDEX idx_chat_history_projections_session
    ON chat_history_projections (session_id, created_at);
