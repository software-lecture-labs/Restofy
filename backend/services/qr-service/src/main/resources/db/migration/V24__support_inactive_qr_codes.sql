ALTER TABLE qr_codes DROP CONSTRAINT ck_qr_codes_status;
ALTER TABLE qr_codes ADD CONSTRAINT ck_qr_codes_status
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'REVOKED'));

ALTER TABLE qr_codes DROP CONSTRAINT ck_qr_codes_revocation;
ALTER TABLE qr_codes ADD CONSTRAINT ck_qr_codes_revocation
    CHECK ((status IN ('ACTIVE', 'INACTIVE') AND revoked_at IS NULL) OR
           (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_at >= issued_at));
