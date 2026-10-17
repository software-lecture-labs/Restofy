package com.restofy.kitchen.api.exception;

import com.restofy.kitchen.domain.exception.CommandIdConflictException;
import com.restofy.kitchen.domain.exception.InvalidKitchenTransitionException;
import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders standard MVC failures (bad parameter, type mismatch, validation, unsupported method) as RFC 9457
 * ProblemDetail without stack traces or internals. Authentication/authorization failures (401/403) are produced
 * by the security filter chain before reaching this advice.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(KitchenOrderNotFoundException.class)
	ProblemDetail orderNotFound(KitchenOrderNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	/** A command that the order's current status does not allow. Nothing was changed. */
	@ExceptionHandler(InvalidKitchenTransitionException.class)
	ProblemDetail invalidTransition(InvalidKitchenTransitionException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setProperty("currentStatus", e.getCurrentStatus().name());
		return problem;
	}

	/** The Idempotency-Key belongs to a different command (other order, other actor or other transition). */
	@ExceptionHandler(CommandIdConflictException.class)
	ProblemDetail commandIdConflict(CommandIdConflictException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	/** Lost an optimistic-lock race or timed out waiting for the order row lock. Nothing was changed. */
	@ExceptionHandler({ ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class })
	ProblemDetail concurrentChange() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The order was changed concurrently or is busy; reload it and retry if needed");
	}
}
