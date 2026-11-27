-- Puts ONE failed Kitchen -> Order delivery back in the queue, after the cause has been fixed (e.g. a long Order outage).
-- Only for an operator who already has write access to the Kitchen database. Nothing else changes: the stored event,
-- its event id and its body stay as they are, so the Order service receives exactly what it may already have received.
-- Only a FAILED delivery to ORDER of the given restaurant is touched; it gets a fresh attempt budget and is due at once.
-- The previous attempt count, error code and the database user are returned: keep them in the incident record.
-- No row returned = nothing with these ids could be requeued. Requeue PREPARING before READY of the same order.
--
-- psql -X -v ON_ERROR_STOP=1 -d <KITCHEN_DATABASE> -v restaurant_id=<RESTAURANT_UUID> -v event_id=<EVENT_UUID> -f requeue-order-delivery.sql
UPDATE outbox_deliveries AS d
SET status = 'PENDING', attempt_count = 0, next_attempt_at = now(), last_error_code = NULL, version = d.version + 1
FROM outbox_deliveries AS previous
WHERE previous.id = d.id
  AND d.restaurant_id = :'restaurant_id'::uuid
  AND d.event_id = :'event_id'::uuid
  AND d.destination = 'ORDER'
  AND d.status = 'FAILED'
RETURNING d.id AS delivery_id, d.event_id, previous.attempt_count AS previous_attempt_count,
          previous.last_error_code AS previous_error_code, previous.last_attempt_at AS previous_attempt_at,
          current_user AS requeued_by, now() AS requeued_at;
