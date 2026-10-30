-- Order Service ADR-QR-001 (K8, K9; range V41-V50): durable record of a table-session closure, so that a closure the
-- QR Service prepared is remembered across restarts and lost responses, and so that no order is accepted for a table
-- session whose closure is prepared or completed.
--
-- The Order Service owns orders and decides, from them, whether a table session may be closed. The QR Service owns the
-- table and its sessions and performs the closing; it names one operation (operation_id) per closure, so asking again
-- with the same id never prepares or closes a second time. restaurant_id and table_session_id are logical REFs to the
-- QR Service (no cross-DB FK).
--
-- Only an ELIGIBLE closure is stored:
--   PREPARED   new orders for the session are refused (checkout answers 409 TABLE_SESSION_CLOSING); the facts it was
--              decided on are pinned by scope_fingerprint
--   COMPLETED  the QR Service confirmed the table session is closed; final
--   RELEASED   the QR Service confirmed the table session did NOT close; final, orders are accepted again
-- A PREPARED closure never expires by itself: a timeout does not prove the closing failed (K9). At most one closure per
-- table session is PREPARED or COMPLETED at any time, enforced here, whatever the application code does.
-- Checkout and closure preparation are ordered by a database advisory lock per table session (see the service code);
-- this table holds the durable outcome of that ordering.

CREATE TABLE table_session_closures (
    operation_id       uuid         NOT NULL,
    restaurant_id      uuid         NOT NULL,
    table_session_id   uuid         NOT NULL,
    status             varchar(10)  NOT NULL,
    order_count        integer      NOT NULL,
    scope_fingerprint  varchar(64)  NOT NULL,
    prepared_at        timestamptz  NOT NULL,
    completed_at       timestamptz,
    released_at        timestamptz,
    CONSTRAINT pk_table_session_closures PRIMARY KEY (operation_id),
    CONSTRAINT ck_table_session_closures_status CHECK (status IN ('PREPARED', 'COMPLETED', 'RELEASED')),
    CONSTRAINT ck_table_session_closures_count CHECK (order_count > 0),
    CONSTRAINT ck_table_session_closures_fingerprint CHECK (scope_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_table_session_closures_times CHECK (
        (status = 'PREPARED' AND completed_at IS NULL AND released_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL AND completed_at IS NULL))
);

-- One live closure per table session: a second PREPARED or COMPLETED one is impossible.
CREATE UNIQUE INDEX uq_table_session_closures_live
    ON table_session_closures (restaurant_id, table_session_id) WHERE status IN ('PREPARED', 'COMPLETED');

CREATE INDEX ix_table_session_closures_session ON table_session_closures (restaurant_id, table_session_id);

-- A closure is never deleted and its identity and facts never change. PREPARED may move once, to COMPLETED or RELEASED;
-- after that it is final.
CREATE FUNCTION table_session_closures_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'table session closures are never deleted' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id OR NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id
        OR NEW.table_session_id IS DISTINCT FROM OLD.table_session_id
        OR NEW.order_count IS DISTINCT FROM OLD.order_count
        OR NEW.scope_fingerprint IS DISTINCT FROM OLD.scope_fingerprint
        OR NEW.prepared_at IS DISTINCT FROM OLD.prepared_at THEN
        RAISE EXCEPTION 'table session closure % is immutable', OLD.operation_id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status <> 'PREPARED' AND (NEW.status IS DISTINCT FROM OLD.status
        OR NEW.completed_at IS DISTINCT FROM OLD.completed_at OR NEW.released_at IS DISTINCT FROM OLD.released_at) THEN
        RAISE EXCEPTION 'table session closure % is already %, its outcome is final', OLD.operation_id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_table_session_closures_guard
    BEFORE UPDATE OR DELETE ON table_session_closures
    FOR EACH ROW EXECUTE FUNCTION table_session_closures_guard();
