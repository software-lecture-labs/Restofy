-- Closure events are read by an ascending event_seq cursor, but an identity value is taken at insert time, not at
-- commit. Without a lock a later event of the same restaurant could commit first, the reader would move its cursor
-- past it, and the earlier event would never be read when it commits. A transaction-scoped advisory lock per
-- restaurant, taken before the sequence value is drawn, makes event_seq order equal commit order for each restaurant.
-- The lock is released at commit or rollback. The cursor is per restaurant, so other restaurants are not serialized.
CREATE OR REPLACE FUNCTION record_table_session_closure_event() RETURNS trigger AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('restofy.qr.closure-events:' || NEW.restaurant_id::text, 0));
    INSERT INTO table_session_closure_events (restaurant_id, table_id, table_session_id, final_status, occurred_at)
    VALUES (NEW.restaurant_id, NEW.table_id, NEW.id, NEW.status, NEW.closed_at)
    ON CONFLICT (table_session_id) DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
