package com.restofy.auth.api.exception;

import java.util.Arrays;
import java.util.stream.Collectors;

import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.infrastructure.security.MfaUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class AuthExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AuthExceptionHandler.class);
    private static final int MAX_CAUSE_DEPTH = 10;
    private static final int LOGGED_STACK_FRAMES = 5;

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ProblemDetail> unauthorized() {
        return response(HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden() {
        return response(HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalid() {
        return response(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> conflict() {
        return response(HttpStatus.CONFLICT);
    }

    @ExceptionHandler(LoginLimitException.class)
    ResponseEntity<ProblemDetail> limited(LoginLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Cache-Control", "no-store")
                .header("Retry-After", Long.toString(exception.retryAfter()))
                .body(AuthProblems.detail(HttpStatus.TOO_MANY_REQUESTS));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> failure(Exception exception, HttpServletRequest request) {
        if (LOG.isErrorEnabled()) {
            LOG.error("Unhandled error method={} path={} {}", request.getMethod(), request.getRequestURI(),
                    safeSummary(exception));
        }
        return response(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(MfaUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException exception) {
        return response(HttpStatus.valueOf(exception.getStatusCode().value()));
    }

    /**
     * Exception messages can carry user input or SQL values, so only the types and the first stack frames of the
     * root cause are logged. That is enough to locate the failure without leaking data.
     */
    static String safeSummary(Throwable exception) {
        Throwable root = exception;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < MAX_CAUSE_DEPTH; depth++) {
            root = root.getCause();
        }
        String frames = Arrays.stream(root.getStackTrace()).limit(LOGGED_STACK_FRAMES)
                .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                .collect(Collectors.joining(" <- "));
        return "type=" + exception.getClass().getName() + " rootType=" + root.getClass().getName() + " at=" + frames;
    }

    private ResponseEntity<ProblemDetail> response(HttpStatus status) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(AuthProblems.detail(status));
    }
}
