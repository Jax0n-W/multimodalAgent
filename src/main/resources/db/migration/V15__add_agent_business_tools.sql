CREATE TABLE consultant_schedule_slots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    consultant_display_name VARCHAR(120) NOT NULL,
    specialty VARCHAR(160) NOT NULL,
    start_at TIMESTAMP(6) NOT NULL,
    end_at TIMESTAMP(6) NOT NULL,
    modality VARCHAR(32) NOT NULL,
    location VARCHAR(160),
    available BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_consultant_schedule_slots PRIMARY KEY (id)
);

CREATE INDEX idx_consultant_schedule_available_start
    ON consultant_schedule_slots (available, start_at);

CREATE TABLE crisis_tickets (
    ticket_id VARCHAR(40) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    runtime_config_snapshot_id VARCHAR(96) NOT NULL,
    summary LONGTEXT NOT NULL,
    urgency VARCHAR(20) NOT NULL,
    contact_consent BOOLEAN NOT NULL,
    preferred_contact VARCHAR(120),
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_crisis_tickets PRIMARY KEY (ticket_id),
    CONSTRAINT uk_crisis_tickets_run_call UNIQUE (run_id, tool_call_id)
);

CREATE INDEX idx_crisis_tickets_status_created
    ON crisis_tickets (status, created_at);

CREATE INDEX idx_crisis_tickets_user_created
    ON crisis_tickets (user_id, created_at);

-- Deliberately no foreign keys to Hibernate-managed legacy account/session tables.
