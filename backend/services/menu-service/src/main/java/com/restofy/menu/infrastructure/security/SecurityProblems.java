package com.restofy.menu.infrastructure.security;

import com.restofy.menu.domain.exception.ForbiddenOperationException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The RFC 9457 answer of the security filters, so a refusal that never reaches a controller looks like every other
 * Menu error. The text is fixed (the same sentences {@code ApiExceptionHandler} uses): no request value, token,
 * cookie, key or reason for the refusal is ever echoed, and a caller cannot tell a bad signature from an expired
 * token.
 */
public final class SecurityProblems {

    private SecurityProblems() { }

    public static void unauthenticated(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "Unauthenticated", new UnauthenticatedException().getMessage());
    }

    public static void forbidden(HttpServletResponse response) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "Forbidden", new ForbiddenOperationException().getMessage());
    }

    private static void write(HttpServletResponse response, HttpStatus status, String title, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\"}");
    }
}
