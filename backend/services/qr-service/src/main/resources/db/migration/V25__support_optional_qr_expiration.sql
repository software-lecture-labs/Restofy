ALTER TABLE qr_codes ADD COLUMN expires_at TIMESTAMPTZ;

ALTER TABLE qr_codes ADD CONSTRAINT ck_qr_codes_expiration
    CHECK (expires_at IS NULL OR (isfinite(expires_at) AND expires_at > issued_at));
