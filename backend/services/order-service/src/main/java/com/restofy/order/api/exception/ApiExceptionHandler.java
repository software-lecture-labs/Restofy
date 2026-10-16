package com.restofy.order.api.exception;

import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Maps every failure to the single {@link ErrorResponse} body. Framework exceptions get fixed, safe messages;
 * unexpected errors are logged with the correlation id and reported as a generic 500.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> handleBusiness(BusinessException ex) {
        log.info("Request rejected: {}", ex.code());
        return respond(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    ResponseEntity<Object> handleOptimisticLock(Exception ex) {
        log.info("Concurrent modification rejected");
        return respond(ErrorCode.CART_VERSION_CONFLICT,
                "The cart was changed in the meantime; reload it and try again", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation rejected (correlationId={})", MDC.get(CorrelationIdFilter.MDC_KEY));
        return respond(ErrorCode.CONFLICT, "The request conflicts with the current state, please retry", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error (correlationId={})", MDC.get(CorrelationIdFilter.MDC_KEY), ex);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, "The request failed validation", Map.of("fields", fields));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                           HttpHeaders headers, HttpStatusCode status,
                                                                           WebRequest request) {
        return respond(ErrorCode.VALIDATION_ERROR, "The request failed validation", null);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = switch (statusCode.value()) {
            case 400 -> ErrorCode.INVALID_REQUEST;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
        String message = switch (code) {
            case INVALID_REQUEST -> "The request is malformed or contains unsupported fields";
            case NOT_FOUND -> "The resource does not exist";
            case METHOD_NOT_ALLOWED -> "The HTTP method is not supported for this resource";
            case UNSUPPORTED_MEDIA_TYPE -> "The content type is not supported";
            default -> "An unexpected error occurred";
        };
        ResponseEntity<Object> response = respond(code, message, null);
        return ResponseEntity.status(statusCode).headers(headers).body(response.getBody());
    }

    private static ResponseEntity<Object> respond(ErrorCode code, String message, Map<String, Object> details) {
        ErrorResponse body = ErrorResponse.of(code, message, details, MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.status(HttpStatus.valueOf(code.httpStatus())).body(body);
    }
}
