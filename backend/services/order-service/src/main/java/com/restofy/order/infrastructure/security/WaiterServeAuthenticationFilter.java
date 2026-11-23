package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

final class WaiterServeAuthenticationFilter extends OncePerRequestFilter {

    static final String AUTHORITY = "WAITER_SERVE_DELEGATED";
    static final String SERVE_PATH = "/api/v1/internal/orders/{orderId}/serve";
    static final String STATUS_PATH = "/api/v1/internal/orders/{orderId}/serve-commands/{commandId}";
    static final String STAFF_HEADER = "X-Restofy-Staff-Delegation";

    private static final String SERVICE_PREFIX = "Service ";
    private static final PathPatternRequestMatcher SERVE =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, SERVE_PATH);
    private static final PathPatternRequestMatcher STATUS =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, STATUS_PATH);

    private final WaiterServeSignatureVerifier signatures;
    private final AuthContextPort auth;
    private final ApiErrorWriter errors;

    WaiterServeAuthenticationFilter(WaiterServeSignatureVerifier signatures, AuthContextPort auth,
                                    ApiErrorWriter errors) {
        this.signatures = signatures;
        this.auth = auth;
        this.errors = errors;
    }

    static boolean matches(HttpServletRequest request) {
        return SERVE.matches(request) || STATUS.matches(request);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String authorization = singleHeader(request, HttpHeaders.AUTHORIZATION);
        String delegated = singleHeader(request, STAFF_HEADER);
        if (!ServiceAuthenticationFilter.isServiceScheme(authorization) || delegated == null || delegated.isBlank()
                || delegated.length() > WaiterServeSignatureVerifier.MAX_TOKEN_LENGTH) {
            requireIdentity(response);
            return;
        }
        var signed = signatures.verify(authorization.substring(SERVICE_PREFIX.length()).trim()).orElse(null);
        if (signed == null) {
            requireIdentity(response);
            return;
        }

        String query = request.getQueryString();
        String target = request.getRequestURI() + (query == null ? "" : "?" + query);
        if (!request.isSecure() || request.getHeader("Origin") != null || request.getHeader("Cookie") != null
                || request.getHeader(HttpHeaders.CONTENT_ENCODING) != null
                || !request.getMethod().equals(signed.method()) || !target.equals(signed.target())
                || !SignedServiceRequest.digest(delegated.getBytes(StandardCharsets.UTF_8)).equals(signed.staffDigest())) {
            rejectScope(response);
            return;
        }

        byte[] body = request.getInputStream().readNBytes(SignedServiceRequest.MAX_BODY_BYTES + 1);
        if (body.length > SignedServiceRequest.MAX_BODY_BYTES) {
            errors.write(response, ErrorCode.INVALID_REQUEST, "The signed request body is too large");
            return;
        }
        if ((STATUS.matches(request) && body.length != 0)
                || !SignedServiceRequest.digest(body).equals(signed.bodyDigest())) {
            rejectScope(response);
            return;
        }

        Optional<StaffActor> staff;
        try {
            staff = auth.resolveStaff(delegated);
        } catch (BusinessException ex) {
            errors.write(response, ex.code(), "Delegated staff validation failed");
            return;
        } catch (RuntimeException ex) {
            errors.write(response, ErrorCode.DEPENDENCY_UNAVAILABLE, "Delegated staff validation is unavailable");
            return;
        }
        if (staff.isEmpty()) {
            requireIdentity(response);
            return;
        }
        StaffActor actor = staff.get();
        if (!actor.restaurantId().equals(signed.restaurantId())) {
            rejectScope(response);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                actor, null, AuthorityUtils.createAuthorityList(AUTHORITY)));
        chain.doFilter(new SignedServiceRequest(request, body), response);
    }

    private void requireIdentity(HttpServletResponse response) throws IOException {
        errors.unauthenticated(response, "A valid Waiter signature and delegated staff session are required",
                ApiErrorWriter.SERVICE_SCHEME);
    }

    private void rejectScope(HttpServletResponse response) throws IOException {
        errors.write(response, ErrorCode.ACCESS_DENIED, "Waiter identity does not authorise this request");
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var headers = request.getHeaders(name);
        if (!headers.hasMoreElements()) {
            return null;
        }
        String value = headers.nextElement();
        return headers.hasMoreElements() || value.contains(",") ? null : value;
    }
}
