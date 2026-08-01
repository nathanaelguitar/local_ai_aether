-- founding_members: one row per fulfilled Founding Member purchase.
-- Created only from a verified webhook event, never from the browser
-- redirect alone. premium_activated_at / premium_expires_at stay NULL at
-- purchase time — the three-month Premium period starts on activation,
-- not on payment, and activation is a separate operation.
CREATE TABLE IF NOT EXISTS founding_members (
    id                          TEXT    PRIMARY KEY,
    email                       TEXT    NOT NULL,
    normalized_email            TEXT    NOT NULL,
    internal_user_id            TEXT,
    stripe_customer_id          TEXT,
    stripe_checkout_session_id  TEXT    NOT NULL,
    stripe_payment_intent_id    TEXT,
    stripe_event_id             TEXT    NOT NULL,
    checkout_reference_id       TEXT    NOT NULL,
    amount_paid                 INTEGER NOT NULL,
    currency                    TEXT    NOT NULL,
    payment_status              TEXT    NOT NULL,
    member_status               TEXT    NOT NULL DEFAULT 'paid_waiting_for_beta',
    offer_version                TEXT    NOT NULL,
    purchased_at                 TEXT    NOT NULL,
    fulfilled_at                 TEXT    NOT NULL,
    beta_invited_at             TEXT,
    premium_activated_at        TEXT,
    premium_expires_at          TEXT,
    created_at                  TEXT    NOT NULL,
    updated_at                  TEXT    NOT NULL
);

-- One founding_members row per Checkout Session and per fulfilling event,
-- so a retried webhook delivery cannot create a second record.
CREATE UNIQUE INDEX IF NOT EXISTS idx_founding_members_session
    ON founding_members (stripe_checkout_session_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_founding_members_event
    ON founding_members (stripe_event_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_founding_members_payment_intent
    ON founding_members (stripe_payment_intent_id)
    WHERE stripe_payment_intent_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_founding_members_email
    ON founding_members (normalized_email);

-- webhook_events: every Stripe event this Worker has seen, independent of
-- whether it resulted in a founding_members row (e.g. a failed or expired
-- session still gets logged here). Primary key on the Stripe event ID makes
-- "have I already processed this delivery" a single indexed lookup, which
-- is what makes the webhook handler idempotent under Stripe's at-least-once
-- retry behavior.
CREATE TABLE IF NOT EXISTS webhook_events (
    stripe_event_id TEXT    PRIMARY KEY,
    event_type      TEXT    NOT NULL,
    received_at     TEXT    NOT NULL,
    outcome         TEXT    NOT NULL
);

-- checkout_attempts: hashed client IPs, for per-IP throttling of Checkout
-- Session creation (abuse / card-testing protection). IPs are HMAC-SHA-256
-- pseudonyms keyed by RATE_LIMIT_SALT, so common IPs cannot be enumerated
-- from leaked database contents.
CREATE TABLE IF NOT EXISTS checkout_attempts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ip_hash      TEXT    NOT NULL,
    attempt_type TEXT    NOT NULL,
    attempted_at TEXT    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_checkout_attempts_ip
    ON checkout_attempts (ip_hash, attempt_type, attempted_at);

-- A short-lived reservation closes the race where multiple buyers can all
-- pass a count-then-create capacity check for the final place. The INSERT
-- that creates a reservation also checks capacity in the same SQL statement,
-- so D1 serializes the decision. Reservations outlive the corresponding
-- 35-minute Stripe Checkout Session by five minutes and are then reusable.
CREATE TABLE IF NOT EXISTS checkout_reservations (
    id                          TEXT PRIMARY KEY,
    stripe_checkout_session_id TEXT UNIQUE,
    reserved_at                 TEXT NOT NULL,
    expires_at                  TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_checkout_reservations_expiry
    ON checkout_reservations (expires_at);

-- No email provider exists yet. Fulfillment atomically queues one onboarding
-- email so a future sender can deliver it without changing payment logic.
CREATE TABLE IF NOT EXISTS email_outbox (
    id            TEXT PRIMARY KEY,
    member_id     TEXT NOT NULL,
    message_type  TEXT NOT NULL,
    recipient     TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'pending',
    attempts      INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT NOT NULL,
    sent_at       TEXT,
    last_error    TEXT,
    FOREIGN KEY (member_id) REFERENCES founding_members(id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_email_outbox_member_type
    ON email_outbox (member_id, message_type);
