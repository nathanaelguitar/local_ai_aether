-- Delivery state for the verified-payment onboarding email. The sender is
-- intentionally separate from Stripe fulfillment so a provider outage never
-- rolls back the Founding Member record.
ALTER TABLE email_outbox ADD COLUMN updated_at TEXT;
ALTER TABLE email_outbox ADD COLUMN next_attempt_at TEXT;

UPDATE email_outbox
SET updated_at = COALESCE(updated_at, created_at),
    next_attempt_at = COALESCE(next_attempt_at, created_at);
