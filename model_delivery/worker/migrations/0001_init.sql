-- install_tokens: one row per beta install.
-- Stores only the SHA-256 hash of the raw bearer token; the raw value is
-- never persisted anywhere server-side.
CREATE TABLE IF NOT EXISTS install_tokens (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    token_hash TEXT    NOT NULL UNIQUE,
    install_id TEXT    NOT NULL,
    created_at TEXT    NOT NULL,
    revoked    INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tokens_install_id ON install_tokens (install_id);

-- manifest_issuances: every successful manifest response is recorded here
-- for per-install per-version rate limiting (24/24 h).
CREATE TABLE IF NOT EXISTS manifest_issuances (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    token_hash    TEXT NOT NULL,
    model_version TEXT NOT NULL,
    issued_at     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_issuances_token_ver
    ON manifest_issuances (token_hash, model_version, issued_at);

-- registration_attempts: hashed client IPs, for per-IP registration throttle
-- (5/hour). IPs are SHA-256 hashed before storage.
CREATE TABLE IF NOT EXISTS registration_attempts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ip_hash      TEXT NOT NULL,
    install_id   TEXT,
    attempted_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_reg_attempts_ip
    ON registration_attempts (ip_hash, attempted_at);
