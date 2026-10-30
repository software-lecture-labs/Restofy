ALTER TABLE staff_users ADD COLUMN password_changed_at timestamptz;

CREATE TABLE password_reset_requests (
    id uuid PRIMARY KEY,
    staff_user_id uuid NOT NULL REFERENCES staff_users(id),
    cookie_digest varchar(64) NOT NULL UNIQUE CHECK (cookie_digest ~ '^[0-9a-f]{64}$'),
    code_digest varchar(64) NOT NULL CHECK (code_digest ~ '^[0-9a-f]{64}$'),
    locale varchar(2) NOT NULL CHECK (locale IN ('tr','en')),
    status varchar(24) NOT NULL CHECK (status IN ('CODE_PENDING','CODE_VERIFIED','COMPLETED','INVALIDATED','EXPIRED','ATTEMPTS_EXHAUSTED')),
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    created_at timestamptz NOT NULL,
    code_expires_at timestamptz NOT NULL,
    verified_at timestamptz,
    grant_expires_at timestamptz,
    used_at timestamptz,
    CHECK (code_expires_at = created_at + interval '15 minutes'),
    CHECK ((verified_at IS NULL) = (grant_expires_at IS NULL)),
    CHECK (grant_expires_at IS NULL OR grant_expires_at = verified_at + interval '5 minutes'),
    CHECK (status NOT IN ('CODE_VERIFIED','COMPLETED') OR verified_at IS NOT NULL),
    CHECK ((status = 'COMPLETED') = (used_at IS NOT NULL)),
    CHECK (used_at IS NULL OR used_at >= verified_at)
);
CREATE UNIQUE INDEX uq_reset_live_user ON password_reset_requests(staff_user_id)
    WHERE status IN ('CODE_PENDING','CODE_VERIFIED');

CREATE TABLE password_reset_limits (
    bucket_key varchar(64) PRIMARY KEY CHECK (bucket_key ~ '^[0-9a-f]{64}$'),
    attempts integer NOT NULL CHECK (attempts > 0),
    expires_at timestamptz NOT NULL
);
CREATE INDEX ix_reset_limits_expiry ON password_reset_limits(expires_at);
