package com.restofy.auth.api.exception;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.infrastructure.security.MfaUnavailableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {
    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ProblemDetail> unauthorized() { return response(HttpStatus.UNAUTHORIZED); }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden() { return response(HttpStatus.FORBIDDEN); }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid() { return response(HttpStatus.BAD_REQUEST); }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> conflict() { return response(HttpStatus.CONFLICT); }

    @ExceptionHandler(LoginLimitException.class)
    ResponseEntity<ProblemDetail> limited(LoginLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Cache-Control", "no-store")
                .header("Retry-After", Long.toString(exception.retryAfter()))
                .body(AuthProblems.detail(HttpStatus.TOO_MANY_REQUESTS));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> failure() { return response(HttpStatus.INTERNAL_SERVER_ERROR); }

    @ExceptionHandler(MfaUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable() { return response(HttpStatus.SERVICE_UNAVAILABLE); }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException exception) {
        return response(HttpStatus.valueOf(exception.getStatusCode().value()));
    }

    private ResponseEntity<ProblemDetail> response(HttpStatus status) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(AuthProblems.detail(status));
    }
}
