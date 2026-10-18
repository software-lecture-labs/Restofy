package com.restofy.menu.api.exception;

import com.restofy.menu.domain.exception.CategoryNotFoundException;
import com.restofy.menu.domain.exception.CategoryVersionConflictException;
import com.restofy.menu.domain.exception.DuplicateCategoryNameException;
import com.restofy.menu.domain.exception.ForbiddenOperationException;
import com.restofy.menu.domain.exception.InvalidCategoryException;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.DuplicateProductOptionException;
import com.restofy.menu.domain.exception.ProductAvailabilityMissingException;
import com.restofy.menu.domain.exception.ProductAvailabilityVersionConflictException;
import com.restofy.menu.domain.exception.ProductNotFoundException;
import com.restofy.menu.domain.exception.ProductOptionLimitExceededException;
import com.restofy.menu.domain.exception.ProductOptionNotFoundException;
import com.restofy.menu.domain.exception.ProductOptionVersionConflictException;
import com.restofy.menu.domain.exception.ProductVersionConflictException;
import com.restofy.menu.domain.exception.PublicMenuNotFoundException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Single error contract: RFC 9457 {@link ProblemDetail}. Messages are fixed text; request values,
 * SQL, stack traces and internal names are never echoed.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INVALID_REQUEST_TITLE = "Invalid request";

    @ExceptionHandler(UnauthenticatedException.class)
    ProblemDetail unauthenticated(UnauthenticatedException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Unauthenticated", e.getMessage());
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    ProblemDetail forbidden(ForbiddenOperationException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler({CategoryNotFoundException.class, ProductNotFoundException.class, ProductOptionNotFoundException.class,
            PublicMenuNotFoundException.class})
    ProblemDetail notFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler({DuplicateCategoryNameException.class, CategoryVersionConflictException.class,
            ProductVersionConflictException.class, ProductAvailabilityVersionConflictException.class,
            ProductAvailabilityMissingException.class, ProductOptionVersionConflictException.class,
            DuplicateProductOptionException.class, ProductOptionLimitExceededException.class})
    ProblemDetail conflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler({InvalidCategoryException.class, InvalidProductException.class})
    ProblemDetail invalid(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, INVALID_REQUEST_TITLE, e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, INVALID_REQUEST_TITLE, "Request validation failed.");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    /** A path or query value of the wrong type (for example a malformed UUID): fixed text, the value is never echoed. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, INVALID_REQUEST_TITLE, "A request parameter has an invalid value."));
    }

    private Map<String, String> fieldError(FieldError error) {
        return Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage()));
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
