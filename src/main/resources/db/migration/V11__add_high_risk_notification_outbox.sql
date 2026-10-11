CREATE TABLE high_risk_notification_intents (
    notification_id VARCHAR(96) NOT NULL,
    report_id BIGINT NOT NULL,
    alert_record_id BIGINT,
    recipient VARCHAR(240) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    lease_owner VARCHAR(96),
    lease_expires_at TIMESTAMP(6),
    last_failure_reason VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_high_risk_notification_intents PRIMARY KEY (notification_id),
    CONSTRAINT uk_high_risk_notification_report_recipient UNIQUE (report_id, recipient)
);

CREATE INDEX idx_high_risk_notification_due
    ON high_risk_notification_intents (status, next_attempt_at);

CREATE INDEX idx_high_risk_notification_lease
    ON high_risk_notification_intents (status, lease_expires_at);

CREATE INDEX idx_high_risk_notification_report
    ON high_risk_notification_intents (report_id);

-- Deliberately no foreign keys: legacy report/alert tables are still Hibernate-managed and
-- therefore do not exist yet when Flyway migrates a fresh database.
