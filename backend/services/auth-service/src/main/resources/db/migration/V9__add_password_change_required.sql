-- A member whose password was set by an ADMIN (a new account or a temporary password) must choose their own before
-- the session becomes usable. Existing accounts keep their passwords, so they start without the requirement.
ALTER TABLE staff_users ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;

-- PASSWORD_PENDING: a signed-in session that may only change its password, log out or read its own state.
-- The V2 column check is unnamed, so it is found by its single column, as V3 did for the role/state check.
DO $$
DECLARE
    old_constraint text;
    matches integer;
BEGIN
    SELECT count(*), min(conname::text) INTO matches, old_constraint
    FROM pg_constraint
    WHERE conrelid = 'user_sessions'::regclass AND contype = 'c'
      AND conkey = ARRAY[
          (SELECT attnum FROM pg_attribute WHERE attrelid = 'user_sessions'::regclass AND attname = 'auth_state')
      ]::smallint[];
    IF matches <> 1 THEN
        RAISE EXCEPTION 'Unexpected session state constraint';
    END IF;
    EXECUTE format('ALTER TABLE user_sessions DROP CONSTRAINT %I', old_constraint);
END $$;

ALTER TABLE user_sessions ADD CONSTRAINT ck_sessions_auth_state
    CHECK (auth_state IN ('AUTHENTICATED', 'MFA_PENDING', 'PASSWORD_PENDING'));

-- Eviction scans the oldest evictable buckets first (PostgresRateLimitStore.EVICT_OLDEST), so expires_at leads.
DROP INDEX IF EXISTS ix_rate_limits_evictable;
CREATE INDEX ix_rate_limits_eviction ON rate_limits (expires_at, attempts) WHERE evictable AND attempts < max_attempts;
