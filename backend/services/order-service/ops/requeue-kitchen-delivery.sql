-- Run against the Order database using an existing authorised operator role.
-- Required psql variables: restaurant_id and event_id (UUIDs).
-- Inspect the failure and resolve its cause before running this command.
WITH failed AS (
    SELECT d.id, e.restaurant_id, e.aggregate_id AS order_id, e.id AS event_id, e.event_type,
           d.attempt_count, d.last_error_code
    FROM outbox_deliveries d
    JOIN outbox_events e ON e.id = d.event_id
    WHERE e.restaurant_id = :'restaurant_id'::uuid AND e.id = :'event_id'::uuid
      AND e.aggregate_type = 'ORDER'
      AND e.event_type IN ('OrderPlaced', 'OrderCancellationRequested', 'OrderCancelled',
                           'OrderItemsCancelled', 'OrderCancellationRejected')
      AND d.destination = 'KITCHEN' AND d.status = 'FAILED'
    FOR UPDATE OF d
)
UPDATE outbox_deliveries d
SET status = 'PENDING', attempt_count = 0, next_attempt_at = statement_timestamp()
FROM failed f
WHERE d.id = f.id AND d.status = 'FAILED'
RETURNING current_user AS requeued_by, statement_timestamp() AS requeued_at,
          f.restaurant_id, f.order_id, f.event_id, f.event_type,
          f.attempt_count AS previous_attempt_count, f.last_error_code AS previous_error_code;
