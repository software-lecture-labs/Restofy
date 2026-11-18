CREATE TABLE staff_revocation_guard_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    generation uuid,
    ready boolean NOT NULL DEFAULT false,
    database_start timestamptz,
    bootstrap_nonce uuid,
    CHECK (NOT ready OR (generation IS NOT NULL AND database_start IS NOT NULL AND bootstrap_nonce IS NOT NULL))
);
INSERT INTO staff_revocation_guard_state(singleton) VALUES (true);

CREATE TABLE staff_revoked_sessions (
    restaurant_id uuid NOT NULL,
    session_id uuid NOT NULL,
    user_id uuid NOT NULL,
    deny_until timestamptz NOT NULL,
    PRIMARY KEY (restaurant_id, session_id)
);

CREATE TABLE staff_revocation_receipts (
    operation_id uuid PRIMARY KEY,
    generation uuid NOT NULL,
    manifest_digest varchar(64) NOT NULL CHECK (manifest_digest ~ '^[0-9a-f]{64}$'),
    applied_at timestamptz NOT NULL
);
