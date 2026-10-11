ALTER TABLE high_risk_notification_intents
    ADD COLUMN claim_token VARCHAR(64);

-- Existing in-flight rows predate fencing. Give them a non-null generation marker so the
-- lease scanner can safely quarantine them after upgrade; every new claim replaces it.
UPDATE high_risk_notification_intents
SET claim_token = 'legacy-unfenced'
WHERE status IN ('CLAIMED', 'PROCESSING')
  AND claim_token IS NULL;
