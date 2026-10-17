package com.restofy.mail.api.exception;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class MailExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid() { return problem(HttpStatus.BAD_REQUEST); }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException exception) { return problem(HttpStatus.valueOf(exception.getStatusCode().value())); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> failed() { return problem(HttpStatus.INTERNAL_SERVER_ERROR); }
    private ResponseEntity<ProblemDetail> problem(HttpStatus status) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setProperty("code", status.name());
        var builder = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == HttpStatus.SERVICE_UNAVAILABLE) { builder.header("Retry-After", "60"); }
        return builder.body(detail);
    }
}
