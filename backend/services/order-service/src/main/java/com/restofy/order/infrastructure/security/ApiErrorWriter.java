package com.restofy.order.infrastructure.security;

import com.restofy.order.api.exception.ErrorResponse;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.config.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes the API error body from servlet-level code (filters, entry points) that sits outside MVC advice. */
@Component
public class ApiErrorWriter {
    static final String GUEST_SCHEME = "GuestSession";
    static final String STAFF_SCHEME = "Staff";
    static final String SERVICE_SCHEME = "Service";

    private final JsonMapper jsonMapper;

    public ApiErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(code, message, null, MDC.get(CorrelationIdFilter.MDC_KEY)));
    }

    void unauthenticated(HttpServletResponse response, String message, String... schemes) throws IOException {
        for (String scheme : schemes) {
            response.addHeader(HttpHeaders.WWW_AUTHENTICATE, scheme + " realm=\"order\"");
        }
        write(response, ErrorCode.UNAUTHENTICATED, message);
    }
}
