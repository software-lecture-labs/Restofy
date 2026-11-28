package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.OrderEventReceiver;
import com.restofy.kitchen.application.service.OrderEventResult;
import com.restofy.kitchen.domain.exception.InvalidOrderMessageException;
import com.restofy.kitchen.infrastructure.security.OrderEventSecurityConfiguration;
import com.restofy.kitchen.infrastructure.security.VerifiedOrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal receiver of Order's events. The Order service treats 204, and only 204, as delivered, so 204 is returned
 * only for an event that is durably stored. It is reachable only through the security chain of this path, which
 * verifies the signed credential and hands over the signed bytes.
 */
@RestController
public class OrderEventController {

	private static final Logger log = LoggerFactory.getLogger(OrderEventController.class);

	private final OrderEventReceiver receiver;

	public OrderEventController(OrderEventReceiver receiver) {
		this.receiver = receiver;
	}

	@PostMapping(path = OrderEventSecurityConfiguration.PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<ProblemDetail> receive(
			@RequestAttribute(VerifiedOrderRequest.ATTRIBUTE) VerifiedOrderRequest request) {
		OrderEventResult result = receiver.receive(request.restaurantId(), request.body());
		if (result == OrderEventResult.CONFIRMED) {
			return ResponseEntity.noContent().build();
		}
		log.warn("Order event not confirmed: restaurantId={} result={}", request.restaurantId(), result);
		return switch (result) {
			case RESTAURANT_MISMATCH -> refusal(HttpStatus.FORBIDDEN, result,
					"The event does not belong to the signed restaurant");
			case UNSUPPORTED_EVENT_TYPE -> refusal(HttpStatus.UNPROCESSABLE_CONTENT, result,
					"Kitchen cannot apply this event type yet; nothing was changed");
			default -> refusal(HttpStatus.CONFLICT, result, "The event was not applied; nothing was changed");
		};
	}

	@ExceptionHandler(InvalidOrderMessageException.class)
	ResponseEntity<ProblemDetail> invalid(InvalidOrderMessageException e) {
		// The violations name fields and rules, never the received values.
		log.warn("Invalid Order event: {}", e.getViolations());
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "INVALID_ORDER_EVENT",
				"The event does not match the contract; nothing was changed");
		problem.setProperty("violations", e.getViolations());
		return ResponseEntity.badRequest().body(problem);
	}

	/**
	 * Anything unexpected is a server error the caller retries. It is answered here because an unhandled exception
	 * would be dispatched to the error page, where the deny-all chain answers 401. Only the exception type is logged:
	 * database messages can quote order content.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> unexpected(Exception e) {
		log.error("Order event could not be processed: {}", e.getClass().getName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, "2")
				.body(problem(HttpStatus.SERVICE_UNAVAILABLE, "TEMPORARILY_UNAVAILABLE",
						"The event could not be processed now; send the same event again"));
	}

	private static ResponseEntity<ProblemDetail> refusal(HttpStatus status, OrderEventResult result, String detail) {
		return ResponseEntity.status(status).body(problem(status, result.name(), detail));
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return problem;
	}
}
