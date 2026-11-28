package com.restofy.auth.api.exception;

import java.util.Arrays;
import java.util.stream.Collectors;

import com.restofy.auth.application.service.StaffChangeRefusedException;
import com.restofy.auth.application.service.StepUpRejectedException;
import com.restofy.auth.infrastructure.security.LoginRateLimiter.LoginLimitException;
import com.restofy.auth.infrastructure.security.MfaUnavailableException;
import com.restofy.auth.infrastructure.security.RateLimitUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class AuthExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(AuthExceptionHandler.class);
    private static final int MAX_CAUSE_DEPTH = 10;
    private static final int LOGGED_STACK_FRAMES = 5;
    private static final String NO_STORE = "no-store";

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ProblemDetail> unauthorized() { return response(HttpStatus.UNAUTHORIZED); }

    /** A wrong step-up is a 401 like a wrong password, but its reason tells the screen the session is still valid. */
    @ExceptionHandler(StepUpRejectedException.class)
    ResponseEntity<ProblemDetail> stepUpRejected() {
        ProblemDetail problem = AuthProblems.detail(HttpStatus.UNAUTHORIZED);
        problem.setProperty("reason", "STEP_UP_REJECTED");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(problem);
    }

    @ExceptionHandler(StaffChangeRefusedException.class)
    ResponseEntity<ProblemDetail> staffChangeRefused(StaffChangeRefusedException refusal) {
        HttpStatus status = refusal.reason().status();
        ProblemDetail problem = AuthProblems.detail(status);
        problem.setProperty("reason", refusal.reason().name());
        var response = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, NO_STORE);
        if (refusal.reason() == StaffChangeRefusedException.Reason.BUSY) {
            response.header("Retry-After", Long.toString(StaffChangeRefusedException.BUSY_RETRY_AFTER_SECONDS));
        }
        return response.body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> forbidden() { return response(HttpStatus.FORBIDDEN); }

    // A malformed path or query value (for example a staff id that is not a UUID) is a client error, not a 500.
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalid() { return response(HttpStatus.BAD_REQUEST); }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> conflict() { return response(HttpStatus.CONFLICT); }

    @ExceptionHandler(LoginLimitException.class)
    ResponseEntity<ProblemDetail> limited(LoginLimitException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.CACHE_CONTROL, NO_STORE)
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
    ResponseEntity<ProblemDetail> unavailable() { return response(HttpStatus.SERVICE_UNAVAILABLE); }

    @ExceptionHandler(RateLimitUnavailableException.class)
    ResponseEntity<ProblemDetail> limitsUnavailable() { return response(HttpStatus.SERVICE_UNAVAILABLE); }

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
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(AuthProblems.detail(status));
    }
}
