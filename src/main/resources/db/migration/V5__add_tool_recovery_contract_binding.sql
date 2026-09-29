ALTER TABLE tool_executions
    ADD COLUMN recovery_contract_id VARCHAR(96);

ALTER TABLE tool_executions
    ADD COLUMN recovery_contract_schema_version INTEGER;

ALTER TABLE tool_executions
    ADD COLUMN recovery_contract_version VARCHAR(64);

ALTER TABLE tool_executions
    ADD COLUMN replay_semantics VARCHAR(32);

ALTER TABLE tool_executions
    ADD COLUMN reconciliation_supported BOOLEAN;

ALTER TABLE tool_executions
    ADD COLUMN reconciliation_strategy_id VARCHAR(160);
