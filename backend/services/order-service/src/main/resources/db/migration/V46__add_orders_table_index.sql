-- Order Service ORDER-03 (range V41-V50): listing orders of a physical table (all of its table sessions).
-- Listing by table SESSION (restaurant_id, table_session_id, placed_at DESC), by guest and by status is already
-- served by ix_orders_table_session, ix_orders_guest_session and ix_orders_status (V42).
CREATE INDEX ix_orders_table ON orders (restaurant_id, table_id, placed_at DESC);
