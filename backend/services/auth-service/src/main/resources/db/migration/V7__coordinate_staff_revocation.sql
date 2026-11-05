ALTER TABLE staff_users ADD COLUMN revocation_revision bigint NOT NULL DEFAULT 0
    CHECK (revocation_revision >= 0);

CREATE TABLE staff_revocation_operations (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES staff_users(id),
    restaurant_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    action varchar(24) NOT NULL CHECK (action IN ('SESSION','ALL','OTHERS','DISABLE','ROLE')),
    selected_session_id uuid,
    target_role varchar(20) CHECK (target_role IN ('ADMIN','WAITER','KITCHEN')),
    expected_user_version bigint NOT NULL,
    consumer_generation uuid NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PREPARED','ACKNOWLEDGED','COMPLETED')),
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    purpose varchar(16) NOT NULL DEFAULT 'AUTO' CHECK (purpose IN ('AUTO','RESET','MFA')),
    purpose_id uuid,
    resume_until timestamptz,
    business_outcome varchar(16) CHECK (business_outcome IN ('PENDING','APPLIED','EXPIRED')),
    CHECK ((purpose = 'AUTO') = (purpose_id IS NULL)),
    CHECK ((purpose = 'AUTO') = (resume_until IS NULL)),
    CHECK ((purpose = 'AUTO') = (business_outcome IS NULL)),
    UNIQUE (user_id, revision),
    CHECK ((action = 'ROLE') = (target_role IS NOT NULL)),
    CHECK ((action IN ('SESSION','OTHERS')) = (selected_session_id IS NOT NULL)),
    CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_staff_pending_revocation ON staff_revocation_operations(user_id)
    WHERE status <> 'COMPLETED';

CREATE TABLE staff_revocation_targets (
    operation_id uuid NOT NULL REFERENCES staff_revocation_operations(id),
    session_id uuid NOT NULL REFERENCES user_sessions(id),
    deny_until timestamptz NOT NULL,
    PRIMARY KEY (operation_id, session_id)
);

-- A closed source barrier survives crashes; only an acknowledged full baseline opens issuance.
CREATE TABLE staff_revocation_source_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    phase varchar(16) NOT NULL DEFAULT 'CLOSED' CHECK (phase IN ('CLOSED','PREPARING','ACTIVE')),
    bootstrap_id uuid,
    qr_nonce uuid,
    database_start timestamptz,
    restaurant_id uuid,
    generation uuid,
    manifest_digest varchar(64) CHECK (manifest_digest ~ '^[0-9a-f]{64}$'),
    CHECK (phase <> 'ACTIVE' OR (bootstrap_id IS NOT NULL AND qr_nonce IS NOT NULL
        AND database_start IS NOT NULL AND restaurant_id IS NOT NULL AND generation IS NOT NULL AND manifest_digest IS NOT NULL))
);
INSERT INTO staff_revocation_source_state(singleton) VALUES (true);
CREATE TABLE staff_revocation_baseline (
    session_id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    deny_until timestamptz NOT NULL
);
