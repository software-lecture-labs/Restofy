package com.restofy.kitchen.application.port;

import com.restofy.kitchen.application.contract.OutboundMessage;
import com.restofy.kitchen.domain.enums.DeliveryOutcome;

import java.util.Set;

/**
 * Boundary towards ONE other service that receives Kitchen's outbox events. A sink is bound to one destination, and the
 * delivery of an event to that destination is tracked on its own: another destination being down, slow or final changes
 * nothing here. The Order destination has an HTTP implementation ({@code HttpOrderEventSink}); it cannot run in a real
 * environment yet because Kitchen's service identity is not decided (D-09). Notification has no implementation. Every
 * adapter must honour this contract, which the delivery tests pin down:
 * <ul>
 *   <li>Return {@link DeliveryOutcome#DELIVERED} only for a positive acknowledgement of THIS event id.</li>
 *   <li>A timeout, connection problem, 5xx, throttling or any unknown result is {@code RETRYABLE_FAILURE} (throwing
 *       a runtime exception counts as the same): the outcome is unknown, so the same event id is sent again.</li>
 *   <li>Only a definite, non-repairable rejection (e.g. the target says the contract is unsupported) is
 *       {@code PERMANENT_FAILURE}.</li>
 *   <li>A definite answer that the event cannot be accepted YET, because something else has to happen first, is
 *       {@code WAITING}: it uses no attempt, so it can never run out while it waits.</li>
 *   <li>The receiver must treat the event id idempotently: delivery is at-least-once, never exactly-once.</li>
 *   <li>The adapter does not retry on its own and holds no database lock while it works.</li>
 * </ul>
 */
public interface EventSink {

	Result deliver(OutboundMessage message);

	/** The destination this sink serves; the delivery service never hands it a delivery meant for another one. */
	String destination();

	/**
	 * Event types this sink must not be given yet. Their deliveries are not selected and not claimed: they stay PENDING,
	 * use no attempt and do not take the place of other deliveries.
	 */
	default Set<String> heldEventTypes() {
		return Set.of();
	}

	/** Result of one attempt; {@code errorCode} is a short, safe code (never a message, address or token). */
	record Result(DeliveryOutcome outcome, String errorCode) {

		public static Result delivered() {
			return new Result(DeliveryOutcome.DELIVERED, null);
		}

		public static Result retry(String errorCode) {
			return new Result(DeliveryOutcome.RETRYABLE_FAILURE, errorCode);
		}

		public static Result waiting(String errorCode) {
			return new Result(DeliveryOutcome.WAITING, errorCode);
		}

		public static Result permanentFailure(String errorCode) {
			return new Result(DeliveryOutcome.PERMANENT_FAILURE, errorCode);
		}
	}
}
