package com.restofy.qr.api.exception;

import com.restofy.qr.domain.exception.*;
import com.restofy.qr.infrastructure.security.ratelimit.RateLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(StaffClosureException.class)
    ProblemDetail staffClosure(StaffClosureException exception) {
        var detail = problem(exception.status(), exception.getMessage());
        detail.setProperty("code", exception.code().name());
        detail.setProperty("blockers", java.util.List.of());
        return detail;
    }

    /** ADR-QR-002 K7: a limit or the table session capacity was reached. */
    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> tooManyRequests(RateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage()));
    }

    /** ADR-QR-002 K7: the counters could not be used, so the request is refused instead of served without a limit. */
    @ExceptionHandler(com.restofy.qr.infrastructure.security.ratelimit.RateLimitUnavailableException.class)
    ProblemDetail limitsUnavailable(RuntimeException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "The request could not be accepted now. Retry later.");
    }

    @ExceptionHandler({QrImageUnavailableException.class, SessionClosureUnavailableException.class})
    ProblemDetail unavailable(RuntimeException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    /**
     * One generic guest refusal. The stable {@code code} lets callers (Waiter, Order) recognize it without parsing
     * {@code detail}; it deliberately does not say why (unknown, expired, closed or another restaurant's guest).
     */
    @ExceptionHandler(InvalidGuestSessionException.class)
    ResponseEntity<ProblemDetail> guestSessionUnavailable(InvalidGuestSessionException exception, HttpServletRequest request) {
        var refused = unknownOverBudget(request, exception.unknownCredential());
        if (refused != null) {
            return refused;
        }
        var detail = problem(HttpStatus.NOT_FOUND, exception.getMessage());
        detail.setProperty("code", InvalidGuestSessionException.CODE);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(detail);
    }

    /** Same 404 for unknown and unusable QR codes; only the rate limiter learns which one it was. */
    @ExceptionHandler(InvalidQrCodeException.class)
    ResponseEntity<ProblemDetail> invalidQrCode(InvalidQrCodeException exception, HttpServletRequest request) {
        var refused = unknownOverBudget(request, exception.unknownCredential());
        return refused != null ? refused
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(HttpStatus.NOT_FOUND, exception.getMessage()));
    }

    @ExceptionHandler({TableNotFoundException.class, QrCodeNotFoundException.class})
    ProblemDetail notFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * ADR-QR-002 K7: a credential matching nothing of ours costs the address's failure budget (RateLimitFilter). Once
     * that budget is spent, such a credential gets 429 instead of 404; known QR codes and guests never reach this.
     *
     * @return the 429 response, or {@code null} when the usual 404 applies
     */
    private ResponseEntity<ProblemDetail> unknownOverBudget(HttpServletRequest request, boolean unknownCredential) {
        if (!unknownCredential) {
            return null;
        }
        if (request.getAttribute(RateLimitFilter.FAILURE_RETRY_AFTER) instanceof Long retryAfter) {
            return tooManyRequests(new RateLimitExceededException(retryAfter));
        }
        request.setAttribute(RateLimitFilter.UNKNOWN_CREDENTIAL, Boolean.TRUE);
        return null;
    }

    @ExceptionHandler({TableConflictException.class, OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail conflict(Exception exception) {
        return problem(HttpStatus.CONFLICT, "The operation conflicts with the current data. Reload and retry.");
    }

    @ExceptionHandler({QrCodeConflictException.class, TableSessionConflictException.class, SessionClosureConflictException.class})
    ProblemDetail qrConflict(RuntimeException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Request validation failed.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail unauthenticated(AuthenticationException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "Verified management context is required.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Table management access is unavailable.");
    }

    @ExceptionHandler(TableCleanException.class)
    ProblemDetail tableClean(TableCleanException exception) {
        var detail = problem(exception.status(), exception.getMessage());
        detail.setProperty("code", exception.code().name());
        return detail;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        // Exception messages, causes and stack traces can contain credentials or SQL.
        LOG.error("event=QR_UNEXPECTED_ERROR type={}", exception.getClass().getName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        return super.handleExceptionInternal(exception,
                ProblemDetail.forStatusAndDetail(statusCode, "Request could not be processed."), headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
