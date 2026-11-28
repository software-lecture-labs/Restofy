-- ADR-QR-002 K7: one table for every QR rate limit, shared by all QR instances (same model as Auth V8).
-- bucket_key is an HMAC of the limited subject (address, QR, table, guest, staff user), never the subject itself.
-- max_attempts is stored per bucket so that a bucket that reached its limit ("blocked") can be told apart from one
-- that did not: blocked buckets are never evicted to make room for new ones.
CREATE TABLE rate_limits (
    bucket_key   varchar(64) PRIMARY KEY CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
    attempts     integer     NOT NULL CHECK (attempts > 0),
    max_attempts integer     NOT NULL CHECK (max_attempts > 0),
    expires_at   timestamptz NOT NULL
);

CREATE INDEX ix_rate_limits_expiry ON rate_limits (expires_at);
-- Eviction candidates only: blocked buckets (attempts >= max_attempts) are never evicted.
CREATE INDEX ix_rate_limits_evictable ON rate_limits (expires_at, attempts) WHERE attempts < max_attempts;
