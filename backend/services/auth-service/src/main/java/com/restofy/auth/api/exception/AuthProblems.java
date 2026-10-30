package com.restofy.auth.api.exception;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class AuthProblems {
    private AuthProblems() {
    }

    public static ProblemDetail detail(HttpStatus status) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setProperty("code", status.name());
        return problem;
    }

    public static void write(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"status\":" + status + ",\"code\":\""
                + HttpStatus.valueOf(status).name() + "\"}");
    }
}
