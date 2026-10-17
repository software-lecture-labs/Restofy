package com.restofy.kitchen.api.exception;

import com.restofy.kitchen.domain.exception.KitchenOrderNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
}
