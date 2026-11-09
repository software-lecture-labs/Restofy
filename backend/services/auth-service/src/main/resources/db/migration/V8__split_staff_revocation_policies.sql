ALTER TABLE staff_revocation_operations
    ADD COLUMN completion_policy varchar(16) NOT NULL DEFAULT 'ACK_REQUIRED'
        CHECK (completion_policy IN ('LOCAL_FIRST', 'ACK_REQUIRED')),
    ADD COLUMN local_applied_at timestamptz,
    ADD COLUMN delivery_outcome varchar(20)
        CHECK (delivery_outcome IN ('ACKNOWLEDGED', 'TARGETS_EXPIRED', 'NO_TARGETS'));

DROP INDEX uq_staff_pending_revocation;

CREATE UNIQUE INDEX uq_staff_pending_strict_revocation
    ON staff_revocation_operations(user_id)
    WHERE completion_policy = 'ACK_REQUIRED' AND status <> 'COMPLETED';

CREATE UNIQUE INDEX uq_staff_pending_local_logout
    ON staff_revocation_operations(user_id, selected_session_id)
    WHERE completion_policy = 'LOCAL_FIRST' AND action = 'SESSION' AND status <> 'COMPLETED';

CREATE INDEX ix_staff_revocation_delivery_queue
    ON staff_revocation_operations(next_attempt_at, id)
    WHERE status <> 'COMPLETED';

CREATE INDEX ix_staff_revocation_pending_age
    ON staff_revocation_operations(completion_policy, created_at)
    WHERE status <> 'COMPLETED';

UPDATE staff_revocation_operations
SET delivery_outcome = 'ACKNOWLEDGED'
WHERE status = 'COMPLETED';

ALTER TABLE staff_revocation_operations
    ADD CONSTRAINT ck_staff_revocation_local_commit
    CHECK ((completion_policy = 'LOCAL_FIRST') = (local_applied_at IS NOT NULL)),
    ADD CONSTRAINT ck_staff_revocation_delivery_completion
    CHECK ((status = 'COMPLETED') = (delivery_outcome IS NOT NULL));
