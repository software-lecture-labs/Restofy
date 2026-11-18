CREATE TABLE auth_login_limits (
    bucket_key varchar(64) PRIMARY KEY CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
    attempts integer NOT NULL CHECK (attempts > 0),
    expires_at timestamptz NOT NULL
);
CREATE INDEX ix_auth_login_limits_expiry ON auth_login_limits(expires_at);

CREATE TABLE auth_mfa_peer_limits (
    bucket_key varchar(64) PRIMARY KEY CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
    attempts integer NOT NULL CHECK (attempts > 0),
    expires_at timestamptz NOT NULL
);
CREATE INDEX ix_auth_mfa_peer_limits_expiry ON auth_mfa_peer_limits(expires_at);
-- Existing password_reset_limits and its hashes/counters are deliberately left unchanged.
