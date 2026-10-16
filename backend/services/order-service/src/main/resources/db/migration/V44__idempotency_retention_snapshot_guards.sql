-- Order Service V44 (range V41-V50). Does not touch V41-V43; everything here is additive.
--  1. idempotency retention (expires_at, status) so expired records can be purged safely
--  2. price version snapshot (optional, supplied by the Menu Service once it provides one)
--  3. database-level guards: a finished cart can never become ACTIVE again, and the order snapshot is immutable

-- 1. idempotency retention ---------------------------------------------------------------------------------------
ALTER TABLE idempotency_records ADD COLUMN expires_at timestamptz;
ALTER TABLE idempotency_records ADD COLUMN status varchar(20) NOT NULL DEFAULT 'COMPLETED';
-- records written before this migration get the checkout retention (72h) counted from their creation
UPDATE idempotency_records SET expires_at = created_at + interval '72 hours' WHERE expires_at IS NULL;
ALTER TABLE idempotency_records ALTER COLUMN expires_at SET NOT NULL;
ALTER TABLE idempotency_records ADD CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'));
-- the purge job selects COMPLETED rows whose expires_at has passed; IN_PROGRESS rows are never purged
CREATE INDEX ix_idempotency_cleanup ON idempotency_records (status, expires_at);

-- 2. price version snapshot ----------------------------------------------------------------------------------------
ALTER TABLE cart_items ADD COLUMN price_version_snapshot varchar(64);
ALTER TABLE order_items ADD COLUMN price_version varchar(64);

-- 3a. a cart that was ordered or expired can never be reactivated ---------------------------------------------------
CREATE FUNCTION carts_keep_terminal_status() RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'ACTIVE' AND NEW.status IS DISTINCT FROM OLD.status THEN
        RAISE EXCEPTION 'cart % is %, its status can no longer change', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_carts_terminal_status
    BEFORE UPDATE OF status ON carts
    FOR EACH ROW EXECUTE FUNCTION carts_keep_terminal_status();

-- 3b. the order snapshot is immutable: only statuses/due (orders) and cancelled_quantity (order_items) may change ----
CREATE FUNCTION orders_keep_snapshot() RETURNS trigger AS $$
BEGIN
    IF NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id OR NEW.order_no IS DISTINCT FROM OLD.order_no
        OR NEW.cart_id IS DISTINCT FROM OLD.cart_id OR NEW.table_id IS DISTINCT FROM OLD.table_id
        OR NEW.table_session_id IS DISTINCT FROM OLD.table_session_id
        OR NEW.guest_session_id IS DISTINCT FROM OLD.guest_session_id OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.initial_net_minor IS DISTINCT FROM OLD.initial_net_minor
        OR NEW.initial_vat_minor IS DISTINCT FROM OLD.initial_vat_minor
        OR NEW.initial_gross_minor IS DISTINCT FROM OLD.initial_gross_minor
        OR NEW.note IS DISTINCT FROM OLD.note OR NEW.placed_at IS DISTINCT FROM OLD.placed_at THEN
        RAISE EXCEPTION 'the snapshot of order % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_orders_snapshot
    BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_keep_snapshot();

CREATE FUNCTION order_items_keep_snapshot() RETURNS trigger AS $$
BEGIN
    IF NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id
        OR NEW.line_no IS DISTINCT FROM OLD.line_no OR NEW.product_id IS DISTINCT FROM OLD.product_id
        OR NEW.product_name_snapshot IS DISTINCT FROM OLD.product_name_snapshot
        OR NEW.ordered_quantity IS DISTINCT FROM OLD.ordered_quantity OR NEW.currency IS DISTINCT FROM OLD.currency
        OR NEW.unit_gross_minor IS DISTINCT FROM OLD.unit_gross_minor
        OR NEW.tax_rate_bps IS DISTINCT FROM OLD.tax_rate_bps
        OR NEW.line_gross_minor IS DISTINCT FROM OLD.line_gross_minor
        OR NEW.line_net_minor IS DISTINCT FROM OLD.line_net_minor
        OR NEW.line_vat_minor IS DISTINCT FROM OLD.line_vat_minor OR NEW.note IS DISTINCT FROM OLD.note
        OR NEW.price_version IS DISTINCT FROM OLD.price_version THEN
        RAISE EXCEPTION 'the snapshot of order item % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_items_snapshot
    BEFORE UPDATE ON order_items
    FOR EACH ROW EXECUTE FUNCTION order_items_keep_snapshot();

CREATE FUNCTION order_item_options_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'order item options are an immutable snapshot' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_item_options_immutable
    BEFORE UPDATE ON order_item_options
    FOR EACH ROW EXECUTE FUNCTION order_item_options_immutable();
