-- One table for every Auth rate limit, shared by all Auth instances.
-- bucket_key is an HMAC of the limited subject (account, address, ...), never the subject itself.
-- max_attempts is stored per bucket so that a bucket that reached its limit ("blocked") can be told apart from one
-- that did not. Together with evictable it decides what may be dropped when the table is full: buckets that guard
-- secrets against guessing (evictable = false) are never dropped to make room for another limiter's bucket.
CREATE TABLE rate_limits (
    bucket_key   varchar(64) PRIMARY KEY CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
    attempts     integer     NOT NULL CHECK (attempts > 0),
    max_attempts integer     NOT NULL CHECK (max_attempts > 0),
    evictable    boolean     NOT NULL,
    expires_at   timestamptz NOT NULL
);

CREATE INDEX ix_rate_limits_expiry ON rate_limits (expires_at);
-- Eviction candidates only: protected buckets and blocked ones (attempts >= max_attempts) are never evicted.
CREATE INDEX ix_rate_limits_evictable ON rate_limits (attempts, expires_at) WHERE evictable AND attempts < max_attempts;

-- Replaced by rate_limits: the reset limits now use the same store, atomic upserts and no global lock.
-- Their windows last at most a day, so nothing worth keeping is lost.
DROP TABLE IF EXISTS password_reset_limits;
