package com.restofy.order.infrastructure.security;

import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.application.port.ServiceIdentityPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;

/**
 * Authenticates a calling SERVICE from {@code Authorization: Service <credential>} through the
 * {@link ServiceIdentityPort}. It is separate from the guest cookie and staff ({@code Staff}) scheme, so
 * none of the three credentials is ever offered to the wrong verifier. An unknown credential is a 401; an unreachable
 * or failing verifier is a 503 (fail closed). Credentials are never logged.
 */
@Slf4j
public class ServiceAuthenticationFilter extends OncePerRequestFilter {

    static final String SCHEME = "Service ";

    private final ServiceIdentityPort services;
    private final ApiErrorWriter errors;

    public ServiceAuthenticationFilter(ServiceIdentityPort services, ApiErrorWriter errors) {
        this.services = services;
        this.errors = errors;
    }

    static boolean isServiceScheme(String header) {
        return header != null && header.regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return WaiterServeAuthenticationFilter.matches(request) || !request.getRequestURI().startsWith("/api/")
                || !isServiceScheme(request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String credential = request.getHeader(HttpHeaders.AUTHORIZATION).substring(SCHEME.length()).trim();
        int credentialLimit = services instanceof QrServiceIdentityVerifier
                ? QrServiceIdentityVerifier.MAX_TOKEN_LENGTH : SecurityLimits.MAX_CREDENTIAL_LENGTH;
        if (credential.isEmpty() || credential.length() > credentialLimit) {
            reject(response);
            return;
        }
        Optional<ServiceActor> actor;
        try {
            actor = services.resolveService(credential);
        } catch (BusinessException unavailable) {
            errors.write(response, unavailable.code(), unavailable.getMessage());
            return;
        } catch (RuntimeException unexpected) {
            // Fail closed: if the service identity cannot be verified, nothing is allowed through.
            log.error("Service authentication failed unexpectedly", unexpected);
            errors.write(response, ErrorCode.DEPENDENCY_UNAVAILABLE, "Service authentication is currently unavailable");
            return;
        }
        if (actor.isEmpty()) {
            reject(response);
            return;
        }
        if (actor.get().qrRequest() != null) {
            request = verifyQrRequest(request, response, actor.get().qrRequest());
            if (request == null) {
                return;
            }
        }
        SecurityContextHolder.getContext().setAuthentication(new ServiceAuthentication(actor.get()));
        chain.doFilter(request, response);
    }

    private HttpServletRequest verifyQrRequest(HttpServletRequest request, HttpServletResponse response,
                                               ServiceActor.QrRequest signed) throws IOException {
        String query = request.getQueryString();
        String target = request.getRequestURI() + (query == null ? "" : "?" + query);
        if (!request.getMethod().equals(signed.method()) || !target.equals(signed.target())
                || request.getHeader("Origin") != null || request.getHeader("Cookie") != null
                || request.getHeader(HttpHeaders.CONTENT_ENCODING) != null
                || Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size() != 1) {
            errors.write(response, ErrorCode.ACCESS_DENIED, "Service credential does not authorise the request");
            return null;
        }
        byte[] body = request.getInputStream().readNBytes(SignedServiceRequest.MAX_BODY_BYTES + 1);
        if (body.length > SignedServiceRequest.MAX_BODY_BYTES) {
            errors.write(response, ErrorCode.INVALID_REQUEST, "The lifecycle request body is too large");
            return null;
        }
        if (!SignedServiceRequest.digest(body).equals(signed.bodyDigest())
                || ("GET".equals(signed.method()) && body.length != 0)) {
            errors.write(response, ErrorCode.ACCESS_DENIED, "Service credential does not authorise the request");
            return null;
        }
        return new SignedServiceRequest(request, body);
    }

    private void reject(HttpServletResponse response) throws IOException {
        errors.unauthenticated(response, "A valid service credential is required", ApiErrorWriter.SERVICE_SCHEME);
    }
}
