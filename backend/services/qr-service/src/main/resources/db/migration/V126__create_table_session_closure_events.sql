-- Durable, replayable record that a main table session reached a final state (CLOSED or EXPIRED), for the Waiter service.
-- A database trigger writes it in the same transaction as the status change, so every path (staff close, automatic
-- close, idle expiry) is covered and no event can be lost or exist without its transition. No order, payment or guest data.
CREATE TABLE table_session_closure_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_seq BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL,
    restaurant_id UUID NOT NULL,
    table_id UUID NOT NULL,
    table_session_id UUID NOT NULL,
    final_status VARCHAR(10) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_table_session_closure_events_seq UNIQUE (event_seq),
    CONSTRAINT uq_table_session_closure_events_session UNIQUE (table_session_id),
    CONSTRAINT fk_table_session_closure_events_session FOREIGN KEY (restaurant_id, table_id, table_session_id)
        REFERENCES table_sessions (restaurant_id, table_id, id),
    CONSTRAINT ck_table_session_closure_events_status CHECK (final_status IN ('CLOSED', 'EXPIRED')),
    CONSTRAINT ck_table_session_closure_events_time CHECK (isfinite(occurred_at))
);

CREATE INDEX ix_table_session_closure_events_cursor ON table_session_closure_events (restaurant_id, event_seq);

CREATE FUNCTION record_table_session_closure_event() RETURNS trigger AS $$
BEGIN
    INSERT INTO table_session_closure_events (restaurant_id, table_id, table_session_id, final_status, occurred_at)
    VALUES (NEW.restaurant_id, NEW.table_id, NEW.id, NEW.status, NEW.closed_at)
    ON CONFLICT (table_session_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_table_sessions_closure_event
    AFTER UPDATE OF status ON table_sessions
    FOR EACH ROW
    WHEN (NEW.status IN ('CLOSED', 'EXPIRED') AND OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION record_table_session_closure_event();

CREATE FUNCTION guard_table_session_closure_events() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Closure events are immutable' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_table_session_closure_events_immutable
    BEFORE UPDATE OR DELETE ON table_session_closure_events
    FOR EACH ROW EXECUTE FUNCTION guard_table_session_closure_events();
