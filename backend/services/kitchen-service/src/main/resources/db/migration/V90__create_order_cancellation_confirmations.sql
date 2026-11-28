-- KITCHEN-INT-03 (Kitchen range V81-V90, docs/decisions/migrations.md): the Order service's cancellation requests that
-- wait for the kitchen's answer, and that answer.
--
-- A row is NOT a cancellation and not Kitchen's own request (that is cancellation_requests): the Order service asks
-- whether an order in preparation may be cancelled, a kitchen staff member answers once, and the Order service decides.
-- The staff answer (status and answer_*) and the Order service's final word (order_*) are separate facts: an agreed
-- cancellation can still be refused by the Order service. The kitchen order's preparation status is never changed here.
-- What was asked never changes, the answer and the outcome are each written once, and a row is never deleted.
-- order_id / cancellation_id / request_event_id are the Order service's ids (logical references).

CREATE TABLE order_cancellation_confirmations (
    id                   uuid         NOT NULL,
    restaurant_id        uuid         NOT NULL,
    order_id             uuid         NOT NULL,
    cancellation_id      uuid         NOT NULL,   -- the Order service's cancellation id, sent back with the answer
    request_event_id     uuid         NOT NULL,   -- the Order service's event id: a redelivery is recognised by it
    scope                varchar(20)  NOT NULL,
    items                jsonb        NOT NULL,   -- what was asked: [{"orderItemId": ..., "quantity": ...}]
    requested_at         timestamptz  NOT NULL,   -- the Order service's time of the request
    received_at          timestamptz  NOT NULL,
    status               varchar(20)  NOT NULL,
    answer_command_id    uuid,                    -- the staff member's Idempotency-Key
    answered_by_user_id  uuid,
    answered_at          timestamptz,
    rejection_code       varchar(40),
    -- The Order service's final word. APPLIED: it cancelled; REJECTED: it refused (also when the kitchen had agreed);
    -- SUPERSEDED: the order was cancelled by another decision before the kitchen answered. NULL while undecided.
    order_outcome            varchar(20),
    order_outcome_at         timestamptz,   -- Kitchen's time of learning it
    -- Only an Order event carries these, so they can follow the outcome learnt from Order's answer to the kitchen.
    order_decided_at         timestamptz,   -- the Order service's time of the decision
    order_rejection_code     varchar(60),   -- the Order service's own code for a refusal
    order_decision_event_id  uuid,          -- the Order event that carried the decision
    version              bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_order_cancellation_confirmations PRIMARY KEY (id),
    CONSTRAINT uq_order_cancellation_confirmations_cancellation UNIQUE (restaurant_id, cancellation_id),
    CONSTRAINT uq_order_cancellation_confirmations_event UNIQUE (restaurant_id, request_event_id),
    CONSTRAINT uq_order_cancellation_confirmations_command UNIQUE (restaurant_id, answer_command_id),
    CONSTRAINT fk_order_cancellation_confirmations_order FOREIGN KEY (restaurant_id, order_id)
        REFERENCES kitchen_orders (restaurant_id, order_id),
    CONSTRAINT ck_order_cancellation_confirmations_scope CHECK (scope IN ('FULL', 'PARTIAL')),
    CONSTRAINT ck_order_cancellation_confirmations_items CHECK (jsonb_typeof(items) = 'array' AND jsonb_array_length(items) > 0),
    CONSTRAINT ck_order_cancellation_confirmations_status CHECK (status IN ('AWAITING_KITCHEN', 'APPROVED', 'REJECTED')),
    -- Waiting means no answer at all; an answer always has its command, its author and its time.
    CONSTRAINT ck_order_cancellation_confirmations_answer CHECK (
        (status = 'AWAITING_KITCHEN') = (answered_at IS NULL)
        AND (answered_at IS NULL) = (answer_command_id IS NULL)
        AND (answered_at IS NULL) = (answered_by_user_id IS NULL)
    ),
    CONSTRAINT ck_order_cancellation_confirmations_rejection CHECK ((status = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT ck_order_cancellation_confirmations_outcome CHECK (order_outcome IN ('APPLIED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT ck_order_cancellation_confirmations_outcome_time CHECK ((order_outcome IS NULL) = (order_outcome_at IS NULL)),
    CONSTRAINT ck_order_cancellation_confirmations_decision CHECK (
        (order_decision_event_id IS NULL) = (order_decided_at IS NULL)
        AND (order_decision_event_id IS NULL OR order_outcome IS NOT NULL)
        AND (order_rejection_code IS NULL OR (order_outcome = 'REJECTED' AND order_decision_event_id IS NOT NULL))
    )
);

-- At most ONE request per order waits for the kitchen (the Order service keeps one open cancellation per order). A request
-- the Order service has already decided no longer waits, even if nobody answered it.
CREATE UNIQUE INDEX uq_order_cancellation_confirmations_waiting ON order_cancellation_confirmations (restaurant_id, order_id)
    WHERE status = 'AWAITING_KITCHEN' AND order_outcome IS NULL;

CREATE FUNCTION guard_order_cancellation_confirmation_update() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id
        OR NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id
        OR NEW.order_id IS DISTINCT FROM OLD.order_id
        OR NEW.cancellation_id IS DISTINCT FROM OLD.cancellation_id
        OR NEW.request_event_id IS DISTINCT FROM OLD.request_event_id
        OR NEW.scope IS DISTINCT FROM OLD.scope
        OR NEW.items IS DISTINCT FROM OLD.items
        OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
        OR NEW.received_at IS DISTINCT FROM OLD.received_at THEN
        RAISE EXCEPTION 'what the Order service asked is immutable' USING ERRCODE = '23000';
    END IF;
    IF OLD.status <> 'AWAITING_KITCHEN' AND (NEW.status IS DISTINCT FROM OLD.status
        OR NEW.answer_command_id IS DISTINCT FROM OLD.answer_command_id
        OR NEW.answered_by_user_id IS DISTINCT FROM OLD.answered_by_user_id
        OR NEW.answered_at IS DISTINCT FROM OLD.answered_at
        OR NEW.rejection_code IS DISTINCT FROM OLD.rejection_code) THEN
        RAISE EXCEPTION 'an answered confirmation request is final' USING ERRCODE = '23000';
    END IF;
    IF OLD.order_outcome IS NOT NULL THEN
        IF NEW.status IS DISTINCT FROM OLD.status THEN
            RAISE EXCEPTION 'a request the Order service has decided cannot be answered' USING ERRCODE = '23000';
        END IF;
        IF NEW.order_outcome IS DISTINCT FROM OLD.order_outcome
            OR NEW.order_outcome_at IS DISTINCT FROM OLD.order_outcome_at THEN
            RAISE EXCEPTION 'the Order service''s outcome is final' USING ERRCODE = '23000';
        END IF;
    END IF;
    IF OLD.order_decision_event_id IS NOT NULL AND (NEW.order_decision_event_id IS DISTINCT FROM OLD.order_decision_event_id
        OR NEW.order_decided_at IS DISTINCT FROM OLD.order_decided_at
        OR NEW.order_rejection_code IS DISTINCT FROM OLD.order_rejection_code) THEN
        RAISE EXCEPTION 'the Order service''s decision is final' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_order_cancellation_confirmations_guard_update
    BEFORE UPDATE ON order_cancellation_confirmations
    FOR EACH ROW EXECUTE FUNCTION guard_order_cancellation_confirmation_update();

CREATE FUNCTION guard_order_cancellation_confirmation_delete() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'confirmation requests are an audit trail and are never deleted' USING ERRCODE = '23000';
END;
$$;

CREATE TRIGGER trg_order_cancellation_confirmations_guard_delete
    BEFORE DELETE ON order_cancellation_confirmations
    FOR EACH ROW EXECUTE FUNCTION guard_order_cancellation_confirmation_delete();
