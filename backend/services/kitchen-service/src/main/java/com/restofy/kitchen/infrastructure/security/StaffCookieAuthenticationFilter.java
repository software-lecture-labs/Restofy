package com.restofy.kitchen.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates staff on the kitchen endpoints from Auth's HttpOnly {@code restofy_access} cookie, and from nothing
 * else: {@code Authorization} (staff, bearer or service) and identity headers such as {@code X-Restaurant-Id},
 * {@code X-Role} or {@code X-User-Id} are never read.
 *
 * <p>No access cookie leaves the request anonymous (401 from the authorization rules). A repeated, invalid, expired
 * or refused credential is 401 here. Auth being unable to answer is 503 and the request goes no further.
 * Credentials are never logged.
 *
 * <p>Created by the security configuration and deliberately not a Spring bean, otherwise Boot would also register
 * it as a global servlet filter.
 */
public class StaffCookieAuthenticationFilter extends OncePerRequestFilter {

	public static final String ACCESS_COOKIE = "restofy_access";
	private static final Logger LOG = LoggerFactory.getLogger(StaffCookieAuthenticationFilter.class);

	private final StaffSessionAuthenticator authenticator;
	private final RequestMatcher staffRoutes;
	private final HandlerExceptionResolver errors;

	StaffCookieAuthenticationFilter(StaffSessionAuthenticator authenticator, RequestMatcher staffRoutes,
			HandlerExceptionResolver errors) {
		this.authenticator = authenticator;
		this.staffRoutes = staffRoutes;
		this.errors = errors;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !staffRoutes.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		List<String> credentials = accessCookies(request);
		if (credentials.isEmpty()) {
			chain.doFilter(request, response);
			return;
		}
		if (credentials.size() != 1) {
			reject(response);
			return;
		}
		Optional<KitchenAuthentication> staff;
		try {
			staff = authenticator.authenticate(credentials.getFirst());
		} catch (StaffAuthUnavailableException unavailable) {
			LOG.warn("Staff session could not be verified with Auth ({})", unavailable.getReason());
			SecurityContextHolder.clearContext();
			if (errors.resolveException(request, response, null, unavailable) == null) {
				// Nothing rendered it: the untouched response would otherwise leave as an empty 200.
				response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			}
			return;
		}
		if (staff.isEmpty()) {
			reject(response);
			return;
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(staff.get());
		SecurityContextHolder.setContext(context);
		chain.doFilter(request, response);
	}

	private static void reject(HttpServletResponse response) {
		SecurityContextHolder.clearContext();
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	}

	private static List<String> accessCookies(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		return cookies == null ? List.of() : Arrays.stream(cookies)
				.filter(cookie -> ACCESS_COOKIE.equals(cookie.getName())).map(Cookie::getValue).toList();
	}
}
