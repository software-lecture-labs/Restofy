CREATE TABLE order_cart_activity_receipts (
    event_id UUID PRIMARY KEY,
    payload_digest VARCHAR(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$')
);
