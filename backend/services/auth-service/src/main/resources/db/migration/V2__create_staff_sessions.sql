CREATE TABLE user_sessions (
    id uuid PRIMARY KEY,
    staff_user_id uuid NOT NULL REFERENCES staff_users(id),
    role_snapshot varchar(20) NOT NULL CHECK (role_snapshot IN ('ADMIN', 'WAITER', 'KITCHEN')),
    auth_state varchar(20) NOT NULL CHECK (auth_state IN ('AUTHENTICATED', 'MFA_PENDING')),
    created_at timestamptz NOT NULL,
    last_activity_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    device_label varchar(100),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (created_at <= last_activity_at AND last_activity_at < absolute_expires_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CHECK ((role_snapshot = 'ADMIN') = (auth_state = 'MFA_PENDING'))
);
CREATE INDEX ix_sessions_user ON user_sessions(staff_user_id);

CREATE TABLE refresh_tokens (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES user_sessions(id),
    token_hash varchar(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    generation bigint NOT NULL CHECK (generation >= 0),
    UNIQUE (session_id, generation),
    CHECK (expires_at > created_at),
    CHECK (used_at IS NULL OR used_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX ix_refresh_session ON refresh_tokens(session_id);
CREATE UNIQUE INDEX uq_refresh_current ON refresh_tokens(session_id)
    WHERE used_at IS NULL AND revoked_at IS NULL;
