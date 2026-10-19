package com.restofy.kitchen.api.exception;

import com.restofy.kitchen.domain.exception.CommandIdConflictException;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders standard MVC failures (bad parameter, type mismatch, validation, unsupported method) as RFC 9457
 * ProblemDetail without stack traces or internals. Authentication/authorization failures (401/403) are produced
 * by the security filter chain before reaching this advice.
 *
 * <p>Conflicts the client must resolve are 409 with a machine-readable {@code code}; a transient lock timeout is a
 * different thing (503 + Retry-After + {@code code=ORDER_BUSY}), so clients can retry the one and not the other.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	public static final String CODE = "code";
	public static final String RETRYABLE = "retryable";
	public static final String ORDER_STATE_CONFLICT = "ORDER_STATE_CONFLICT";
	public static final String COMMAND_ID_CONFLICT = "COMMAND_ID_CONFLICT";
	public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";
	public static final String ORDER_BUSY = "ORDER_BUSY";

	/** Seconds a client should wait before retrying a busy order. */
	static final String RETRY_AFTER_SECONDS = "2";

	@ExceptionHandler(KitchenOrderNotFoundException.class)
	ProblemDetail orderNotFound(KitchenOrderNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	/** A command that the order's current status does not allow. Nothing was changed. Retrying will not help. */
	@ExceptionHandler(InvalidKitchenTransitionException.class)
	ProblemDetail invalidTransition(InvalidKitchenTransitionException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setProperty(CODE, ORDER_STATE_CONFLICT);
		problem.setProperty("currentStatus", e.getCurrentStatus().name());
		problem.setProperty(RETRYABLE, false);
		return problem;
	}

	/** The Idempotency-Key belongs to a different command (other order, other actor or other transition). */
	@ExceptionHandler(CommandIdConflictException.class)
	ProblemDetail commandIdConflict(CommandIdConflictException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setProperty(CODE, COMMAND_ID_CONFLICT);
		problem.setProperty(RETRYABLE, false);
		return problem;
	}

	/** Lost an optimistic-lock race: the order changed under this request. Nothing was changed by it; reload first. */
	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ProblemDetail concurrentModification() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The order was changed by another request; reload it before deciding what to do");
		problem.setProperty(CODE, CONCURRENT_MODIFICATION);
		problem.setProperty(RETRYABLE, true);
		return problem;
	}

	/**
	 * Timed out waiting for the order's row lock. This request changed nothing, but that does not mean the command
	 * failed: another request (possibly the same command) may have succeeded. The same Idempotency-Key can be sent
	 * again safely, or the order reloaded.
	 */
	@ExceptionHandler(PessimisticLockingFailureException.class)
	ResponseEntity<ProblemDetail> orderBusy() {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"The order is busy; this request changed nothing, but another request may have. "
						+ "Retry with the same Idempotency-Key or reload the order");
		problem.setProperty(CODE, ORDER_BUSY);
		problem.setProperty(RETRYABLE, true);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
				.body(problem);
	}
}
