-- QR database ONLY, AFTER restore, with every Auth/QR process and staff ingress stopped.
-- This is an explicit maintenance operation, NOT an automatic rollback detector.
-- Run with psql -X -v ON_ERROR_STOP=1 -d <QR_DATABASE> -f <this-file>.
BEGIN;
SET LOCAL synchronous_commit = 'on';
LOCK TABLE public.staff_revocation_guard_state IN EXCLUSIVE MODE;
DO $$
BEGIN
    IF pg_is_in_recovery() OR current_setting('fsync') <> 'on'
       OR (SELECT count(*) FROM public.staff_revocation_guard_state WHERE singleton) <> 1 THEN
        RAISE EXCEPTION 'Q5 restore quarantine requires one guard row on a durable primary';
    END IF;
END $$;
-- Fresh OS-backed randomness is generated AFTER restore, never copied from the backup.
-- Keeping the registered consumer generation avoids inventing a new consumer identity.
UPDATE public.staff_revocation_guard_state
SET ready=false, bootstrap_nonce=gen_random_uuid(), database_start=pg_postmaster_start_time()
WHERE singleton=true;
COMMIT;
