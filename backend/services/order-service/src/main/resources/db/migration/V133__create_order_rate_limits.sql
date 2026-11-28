CREATE TABLE order_rate_limits (
    operation varchar(32) NOT NULL,
    bucket_key varchar(64) NOT NULL CHECK (length(bucket_key) = 64),
    attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 100001),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (operation, bucket_key)
);

CREATE INDEX ix_order_rate_limits_expiry ON order_rate_limits (expires_at);
