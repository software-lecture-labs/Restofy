-- Order Service ORDER-03 (range V41-V50): ordering of payment events and a database guard for the payment lifecycle.

-- When (by the Payment Service's own clock) the last applied payment event happened. A payment event older than this
-- is a late delivery and must not overwrite a newer payment status, even if its status change looks legal in isolation.
ALTER TABLE orders ADD COLUMN payment_event_at timestamptz;

-- The only payment moves that may ever happen:
--   NOT_REQUESTED > PENDING        a payment attempt started
--   PENDING > PAID | FAILED | CANCELLED
--   FAILED > PENDING               a new attempt after a failure
--   FAILED > CANCELLED             only once the ORDER itself is CANCELLED and nothing was collected; the failed attempt
--                                  is then closed instead of staying FAILED forever
-- PAID and CANCELLED are final. NOT_REQUESTED > CANCELLED is reserved for the order cancellation use case (ORDER-04),
-- which closes the payment axis of an order that never had a payment attempt; no payment event can cause it.
CREATE FUNCTION orders_payment_transition_guard() RETURNS trigger AS $$
BEGIN
    IF NEW.payment_status = OLD.payment_status THEN
        RETURN NEW;
    END IF;
    IF (OLD.payment_status, NEW.payment_status) IN (
            ('NOT_REQUESTED', 'PENDING'), ('PENDING', 'PAID'), ('PENDING', 'FAILED'), ('PENDING', 'CANCELLED'),
            ('FAILED', 'PENDING'), ('NOT_REQUESTED', 'CANCELLED')) THEN
        RETURN NEW;
    END IF;
    IF OLD.payment_status = 'FAILED' AND NEW.payment_status = 'CANCELLED' AND NEW.fulfillment_status = 'CANCELLED' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid payment transition % -> %', OLD.payment_status, NEW.payment_status
        USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_orders_payment_transition_guard
    BEFORE UPDATE OF payment_status ON orders
    FOR EACH ROW EXECUTE FUNCTION orders_payment_transition_guard();
