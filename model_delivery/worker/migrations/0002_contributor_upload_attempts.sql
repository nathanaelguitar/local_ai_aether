-- Upload attempts for the authenticated Contributor Beta relay.
-- Only hashes and timestamps are stored; raw bodies and Authorization values
-- never enter D1.
CREATE TABLE IF NOT EXISTS contributor_upload_attempts (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    token_hash   TEXT NOT NULL,
    ip_hash      TEXT NOT NULL,
    attempted_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_contributor_upload_token_time
    ON contributor_upload_attempts (token_hash, attempted_at);
CREATE INDEX IF NOT EXISTS idx_contributor_upload_ip_time
    ON contributor_upload_attempts (ip_hash, attempted_at);
