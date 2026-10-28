package com.restofy.mail.api.exception;

import java.util.Arrays;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class MailExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(MailExceptionHandler.class);
    private static final String BUSY_RETRY_AFTER_SECONDS = "60";
    private static final int MAX_CAUSE_DEPTH = 10;
    private static final int LOGGED_STACK_FRAMES = 5;

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid() {
        return problem(HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> status(ResponseStatusException exception) {
        return problem(HttpStatus.valueOf(exception.getStatusCode().value()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> failed(Exception exception, HttpServletRequest request) {
        if (LOG.isErrorEnabled()) {
            LOG.error("Unhandled error method={} path={} {}", request.getMethod(), request.getRequestURI(),
                    safeSummary(exception));
        }
        return problem(HttpStatus.INTERNAL_SERVER_ERROR);
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

    private ResponseEntity<ProblemDetail> problem(HttpStatus status) {
        ProblemDetail detail = ProblemDetail.forStatus(status);
        detail.setProperty("code", status.name());
        var builder = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            builder.header("Retry-After", BUSY_RETRY_AFTER_SECONDS);
        }
        return builder.body(detail);
    }
}
