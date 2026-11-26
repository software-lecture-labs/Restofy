package com.restofy.kitchen.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates staff on the kitchen endpoints from Auth's HttpOnly {@code restofy_access} cookie, and from nothing
 * else: {@code Authorization} (staff, bearer or service) and identity headers such as {@code X-Restaurant-Id},
 * {@code X-Role} or {@code X-User-Id} are never read.
 *
 * <p>This is the local proof only and never calls Auth. No access cookie leaves the request anonymous (401 from the
 * authorization rules). A repeated, invalid or expired credential is 401 here. The proof is left on the request for
 * {@link StaffSessionConfirmationFilter}, which runs after authorization. Credentials are never logged.
 *
 * <p>Created by the security configuration and deliberately not a Spring bean, otherwise Boot would also register
 * it as a global servlet filter.
 */
public class StaffCookieAuthenticationFilter extends OncePerRequestFilter {

	public static final String ACCESS_COOKIE = "restofy_access";
	/** Request attribute holding the {@link StaffSessionAuthenticator.StaffProof} of this request. */
	static final String PROOF_ATTRIBUTE = StaffCookieAuthenticationFilter.class.getName() + ".proof";

	private final StaffSessionAuthenticator authenticator;
	private final RequestMatcher staffRoutes;

	StaffCookieAuthenticationFilter(StaffSessionAuthenticator authenticator, RequestMatcher staffRoutes) {
		this.authenticator = authenticator;
		this.staffRoutes = staffRoutes;
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
		Optional<StaffSessionAuthenticator.StaffProof> proof = credentials.size() == 1
				? authenticator.prove(credentials.getFirst()) : Optional.empty();
		if (proof.isEmpty()) {
			reject(response);
			return;
		}
		request.setAttribute(PROOF_ATTRIBUTE, proof.get());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(proof.get().authentication());
		SecurityContextHolder.setContext(context);
		chain.doFilter(request, response);
	}

	static void reject(HttpServletResponse response) {
		SecurityContextHolder.clearContext();
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
	}

	private static List<String> accessCookies(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		return cookies == null ? List.of() : Arrays.stream(cookies)
				.filter(cookie -> ACCESS_COOKIE.equals(cookie.getName())).map(Cookie::getValue).toList();
	}
}
