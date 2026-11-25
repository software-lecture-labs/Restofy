-- When QR first accepted the event. It is the clock of a later, safe clean-up of old receipts. Receipts written
-- before this migration get the migration time, so they are kept at least one full retention window after it.
ALTER TABLE order_cart_activity_receipts
    ADD COLUMN received_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_order_cart_activity_receipts_received_at
    ON order_cart_activity_receipts (received_at);
