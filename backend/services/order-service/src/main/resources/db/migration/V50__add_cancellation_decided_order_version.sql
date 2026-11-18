-- Order Service ORDER-04 / Order <-> Kitchen agreement (last number of the Order range V41-V50, docs/decisions/migrations.md).
--
-- The answer to a Kitchen cancellation request carries the version of the order right after the decision and the time of the
-- decision, and a customer's own wish is a reason of its own:
--   * order_cancellations.decided_order_version: the order version right after the decision, written once. A repeat of the
--     same Kitchen request is answered with the FIRST decision's version and time even if the order moved on since.
--   * the reason catalogue gets CUSTOMER_REQUEST (Kitchen reports it in the same group as CHANGED_MIND).
--
-- V47 was already shared on dev when this agreement was made, so it is NOT edited (its checksum stays as every shared Order
-- database applied it); the change is a new migration that applies on top of V47 in every database, new or existing.

ALTER TABLE order_cancellations ADD COLUMN decided_order_version bigint;

-- the order version of a decision exists only next to a decision and is never negative
ALTER TABLE order_cancellations ADD CONSTRAINT ck_order_cancellations_decided_order_version CHECK (
    decided_order_version IS NULL OR (decided_at IS NOT NULL AND decided_order_version >= 0));

ALTER TABLE order_cancellation_reasons DROP CONSTRAINT ck_order_cancellation_reasons_code;
ALTER TABLE order_cancellation_reasons ADD CONSTRAINT ck_order_cancellation_reasons_code CHECK (
    reason_code IS NULL OR reason_code IN
        ('CHANGED_MIND', 'ORDERED_BY_MISTAKE', 'WAIT_TOO_LONG', 'CUSTOMER_REQUEST', 'ITEM_UNAVAILABLE', 'KITCHEN_ERROR',
         'OTHER'));

-- The guard of V47 plus one rule: once the version of a decision is written it never changes. The trigger of V47 keeps
-- pointing at this function; everything else in it is unchanged.
CREATE OR REPLACE FUNCTION order_cancellations_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'order cancellations are never deleted' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id
        OR NEW.order_id IS DISTINCT FROM OLD.order_id OR NEW.scope IS DISTINCT FROM OLD.scope
        OR NEW.requested_by_type IS DISTINCT FROM OLD.requested_by_type
        OR NEW.requested_by_id IS DISTINCT FROM OLD.requested_by_id
        OR NEW.requested_by_role IS DISTINCT FROM OLD.requested_by_role
        OR NEW.reported_staff_id IS DISTINCT FROM OLD.reported_staff_id
        OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
        OR NEW.kitchen_confirmation_required IS DISTINCT FROM OLD.kitchen_confirmation_required
        OR NEW.fulfillment_status_at_request IS DISTINCT FROM OLD.fulfillment_status_at_request
        OR NEW.payment_status_at_request IS DISTINCT FROM OLD.payment_status_at_request
        OR NEW.currency IS DISTINCT FROM OLD.currency OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'order cancellation % is immutable', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status <> 'REQUESTED' AND (NEW.status IS DISTINCT FROM OLD.status
        OR NEW.decision_source IS DISTINCT FROM OLD.decision_source
        OR NEW.decided_by_type IS DISTINCT FROM OLD.decided_by_type
        OR NEW.decided_by_id IS DISTINCT FROM OLD.decided_by_id
        OR NEW.decided_by_role IS DISTINCT FROM OLD.decided_by_role
        OR NEW.decided_at IS DISTINCT FROM OLD.decided_at
        OR NEW.rejection_code IS DISTINCT FROM OLD.rejection_code
        OR NEW.rejection_note IS DISTINCT FROM OLD.rejection_note
        OR NEW.previous_due_minor IS DISTINCT FROM OLD.previous_due_minor
        OR NEW.cancelled_gross_minor IS DISTINCT FROM OLD.cancelled_gross_minor
        OR NEW.cancelled_net_minor IS DISTINCT FROM OLD.cancelled_net_minor
        OR NEW.cancelled_vat_minor IS DISTINCT FROM OLD.cancelled_vat_minor
        OR NEW.new_due_minor IS DISTINCT FROM OLD.new_due_minor
        OR NEW.collected_minor IS DISTINCT FROM OLD.collected_minor
        OR NEW.refund_required_minor IS DISTINCT FROM OLD.refund_required_minor) THEN
        RAISE EXCEPTION 'order cancellation % is already %, its decision is final', OLD.id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.refunded_minor < OLD.refunded_minor THEN
        RAISE EXCEPTION 'order cancellation %: a refund that happened cannot be taken back', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.staff_reason_added_at IS NOT NULL AND (NEW.staff_reason_added_at IS DISTINCT FROM OLD.staff_reason_added_at
        OR NEW.staff_reason_added_by IS DISTINCT FROM OLD.staff_reason_added_by) THEN
        RAISE EXCEPTION 'order cancellation %: the reason has already been completed', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.decided_order_version IS NOT NULL AND NEW.decided_order_version IS DISTINCT FROM OLD.decided_order_version THEN
        RAISE EXCEPTION 'order cancellation %: the order version of its decision is final', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
