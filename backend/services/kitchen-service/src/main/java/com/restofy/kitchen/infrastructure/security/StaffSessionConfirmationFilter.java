package com.restofy.kitchen.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Confirms the staff session with Auth before anything that is not a reviewed read ({@link StaffReadEndpoints}) is
 * carried out. It sits after the authorization rules, so a request that is refused here already (no CSRF or Origin
 * proof, a bad token, a role without the permission) never reaches Auth. It confirms what
 * {@link StaffCookieAuthenticationFilter} proved on this very request.
 *
 * <p>A refused session is 401. Auth being unable to answer is 503. In both cases the request goes no further.
 *
 * <p>Created by the security configuration and deliberately not a Spring bean.
 */
class StaffSessionConfirmationFilter extends OncePerRequestFilter {

	private static final Logger LOG = LoggerFactory.getLogger(StaffSessionConfirmationFilter.class);

	private final StaffSessionAuthenticator authenticator;
	private final RequestMatcher staffRoutes;
	private final RequestMatcher reads;
	private final HandlerExceptionResolver errors;

	StaffSessionConfirmationFilter(StaffSessionAuthenticator authenticator, RequestMatcher staffRoutes,
			RequestMatcher reads, HandlerExceptionResolver errors) {
		this.authenticator = authenticator;
		this.staffRoutes = staffRoutes;
		this.reads = reads;
		this.errors = errors;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !staffRoutes.matches(request) || reads.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!(request.getAttribute(StaffCookieAuthenticationFilter.PROOF_ATTRIBUTE)
				instanceof StaffSessionAuthenticator.StaffProof proof)) {
			// No staff cookie authenticated this request, so there is no session to confirm.
			chain.doFilter(request, response);
			return;
		}
		boolean confirmed;
		try {
			confirmed = authenticator.confirm(proof);
		} catch (StaffAuthUnavailableException unavailable) {
			LOG.warn("Staff session could not be verified with Auth ({})", unavailable.getReason());
			SecurityContextHolder.clearContext();
			if (errors.resolveException(request, response, null, unavailable) == null) {
				// Nothing rendered it: the untouched response would otherwise leave as an empty 200.
				response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
			return;
		}
		if (!confirmed) {
			StaffCookieAuthenticationFilter.reject(response);
			return;
		}
		chain.doFilter(request, response);
	}
}
