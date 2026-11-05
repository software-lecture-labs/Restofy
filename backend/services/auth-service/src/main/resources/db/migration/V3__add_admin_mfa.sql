DO $$
DECLARE
    old_constraint text;
    matches integer;
BEGIN
    SELECT count(*), min(conname::text) INTO matches, old_constraint
    FROM pg_constraint
    WHERE conrelid = 'user_sessions'::regclass AND contype = 'c'
      AND conkey @> ARRAY[
          (SELECT attnum FROM pg_attribute WHERE attrelid = 'user_sessions'::regclass AND attname = 'role_snapshot'),
          (SELECT attnum FROM pg_attribute WHERE attrelid = 'user_sessions'::regclass AND attname = 'auth_state')
      ]::smallint[];
    IF matches <> 1 THEN
        RAISE EXCEPTION 'Unexpected session role/state constraint';
    END IF;
    EXECUTE format('ALTER TABLE user_sessions DROP CONSTRAINT %I', old_constraint);
END $$;

ALTER TABLE user_sessions ADD CONSTRAINT ck_sessions_pending_admin
    CHECK (auth_state <> 'MFA_PENDING' OR role_snapshot = 'ADMIN');
ALTER TABLE user_sessions ADD CONSTRAINT uq_sessions_user_identity UNIQUE (staff_user_id, id);

CREATE TABLE staff_mfa (
    id uuid PRIMARY KEY,
    staff_user_id uuid NOT NULL UNIQUE REFERENCES staff_users(id),
    encrypted_totp_secret bytea,
    enabled_at timestamptz,
    last_accepted_counter bigint CHECK (last_accepted_counter >= 0),
    pending_session_id uuid,
    pending_created_at timestamptz,
    pending_expires_at timestamptz,
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    failure_window_started_at timestamptz,
    locked_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    FOREIGN KEY (staff_user_id, pending_session_id) REFERENCES user_sessions(staff_user_id, id),
    CHECK (encrypted_totp_secret IS NULL OR octet_length(encrypted_totp_secret) = 48),
    CHECK (updated_at >= created_at),
    CHECK (failed_attempts = 0 OR failure_window_started_at IS NOT NULL),
    CHECK (locked_until IS NULL OR (failed_attempts = 5 AND locked_until > failure_window_started_at)),
    CHECK (
        (enabled_at IS NOT NULL AND encrypted_totp_secret IS NOT NULL AND last_accepted_counter IS NOT NULL
            AND pending_session_id IS NULL AND pending_created_at IS NULL AND pending_expires_at IS NULL)
        OR (enabled_at IS NULL AND last_accepted_counter IS NULL AND (
            (encrypted_totp_secret IS NULL AND pending_session_id IS NULL AND pending_created_at IS NULL AND pending_expires_at IS NULL)
            OR (encrypted_totp_secret IS NOT NULL AND pending_session_id IS NOT NULL AND pending_created_at IS NOT NULL
                AND pending_expires_at IS NOT NULL AND pending_expires_at > pending_created_at)
        ))
    )
);

CREATE TABLE mfa_recovery_codes (
    id uuid PRIMARY KEY,
    staff_user_id uuid NOT NULL REFERENCES staff_users(id),
    code_digest varchar(64) NOT NULL UNIQUE CHECK (code_digest ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    CHECK (used_at IS NULL OR used_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
CREATE INDEX ix_mfa_recovery_user ON mfa_recovery_codes(staff_user_id);
