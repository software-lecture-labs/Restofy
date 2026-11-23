-- An abort may reach Order before the prepare whose response QR is waiting for.
-- Remember that decision without creating a preparation or fencing any checkout.
CREATE TABLE table_session_closure_aborts (
    operation_id     uuid        NOT NULL PRIMARY KEY,
    restaurant_id    uuid        NOT NULL,
    table_session_id uuid        NOT NULL,
    command_digest   varchar(64) NOT NULL CHECK (command_digest ~ '^[0-9a-f]{64}$'),
    aborted_at       timestamptz NOT NULL
);

CREATE FUNCTION table_session_closure_aborts_guard() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'unprepared table session aborts are immutable' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_table_session_closure_aborts_guard
    BEFORE UPDATE OR DELETE ON table_session_closure_aborts
    FOR EACH ROW EXECUTE FUNCTION table_session_closure_aborts_guard();
