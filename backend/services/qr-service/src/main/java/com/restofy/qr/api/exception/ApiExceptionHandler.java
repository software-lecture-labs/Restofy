package com.restofy.qr.api.exception;

import com.restofy.qr.domain.exception.*;
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
    @ExceptionHandler(QrImageUnavailableException.class)
    ProblemDetail imageUnavailable(QrImageUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    @ExceptionHandler({TableNotFoundException.class, QrCodeNotFoundException.class, InvalidQrCodeException.class, InvalidGuestSessionException.class})
    ProblemDetail notFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler({TableConflictException.class, OptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail conflict(Exception exception) {
        return problem(HttpStatus.CONFLICT, "The operation conflicts with the current data. Reload and retry.");
    }

    @ExceptionHandler({QrCodeConflictException.class, TableSessionConflictException.class, OrderAdmissionConflictException.class, OrderOutcomeConflictException.class})
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

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
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
