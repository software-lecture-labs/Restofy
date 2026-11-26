package com.restofy.kitchen.infrastructure.security;

import com.restofy.kitchen.infrastructure.config.KitchenAuthProperties;
import com.restofy.kitchen.support.StaffTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The cookie filter and the confirmation filter on their own, without the rest of the chain. */
class StaffSessionConfirmationFilterTests {

	private final AtomicInteger asked = new AtomicInteger();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private StaffSessionAuthenticator authenticator(StaffSessionCheck check) {
		var tokens = new StaffTokenVerifier(new KitchenAuthProperties("https://auth.example.test",
				StaffTokens.publicKey(), StaffTokens.ISSUER, 300, List.of("http://localhost:5000"), 3000, 5000),
				Clock.systemUTC());
		return new StaffSessionAuthenticator(tokens, value -> {
			asked.incrementAndGet();
			return check.current(value);
		}, Clock.systemUTC());
	}

	/** A request as it leaves the cookie filter: authenticated, with the proof on it. */
	private MockHttpServletRequest authenticated(StaffSessionAuthenticator authenticator) throws Exception {
		var request = new MockHttpServletRequest("PATCH", "/api/v1/kitchen/orders/x/mark-ready");
		request.setCookies(new Cookie(StaffCookieAuthenticationFilter.ACCESS_COOKIE, StaffTokens.access("KITCHEN",
				UUID.randomUUID(), UUID.randomUUID())));
		var reached = new MockFilterChain();
		new StaffCookieAuthenticationFilter(authenticator, any -> true)
				.doFilter(request, new MockHttpServletResponse(), reached);
		assertNotNull(reached.getRequest(), "the cookie filter lets a locally proven request on");
		assertNotNull(SecurityContextHolder.getContext().getAuthentication());
		assertEquals(0, asked.get(), "the cookie filter never calls Auth");
		return request;
	}

	private static StaffSessionConfirmationFilter confirmation(StaffSessionAuthenticator authenticator) {
		return new StaffSessionConfirmationFilter(authenticator, any -> true, any -> false,
				(request, response, handler, error) -> null);
	}

	@Test
	void anOutageNobodyRendersIsStill503AndTheRequestGoesNoFurther() throws Exception {
		var authenticator = authenticator(value -> {
			throw new StaffAuthUnavailableException("status-503");
		});
		var request = authenticated(authenticator);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		confirmation(authenticator).doFilter(request, response, chain);

		assertEquals(503, response.getStatus());
		assertNull(chain.getRequest(), "the request never reaches the application");
		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}

	@Test
	void aRefusedSessionIs401AndTheRequestGoesNoFurther() throws Exception {
		var authenticator = authenticator(value -> Optional.empty());
		var request = authenticated(authenticator);
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		confirmation(authenticator).doFilter(request, response, chain);

		assertEquals(401, response.getStatus());
		assertNull(chain.getRequest());
		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}

	@Test
	void aConfirmedSessionLetsTheRequestOnAfterAskingOnce() throws Exception {
		Instant later = Instant.now().plusSeconds(600);
		var authenticator = authenticator(value -> Optional.of(
				new StaffSessionCheck.SessionState("AUTHENTICATED", "KITCHEN", later, later, null)));
		var request = authenticated(authenticator);
		var chain = new MockFilterChain();

		confirmation(authenticator).doFilter(request, new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest());
		assertEquals(1, asked.get());
	}

	@Test
	void aRequestWithoutAStaffCookieHasNoSessionToConfirm() throws Exception {
		var chain = new MockFilterChain();

		confirmation(authenticator(value -> Optional.empty())).doFilter(
				new MockHttpServletRequest("PATCH", "/api/v1/kitchen/orders/x/mark-ready"), new MockHttpServletResponse(), chain);

		assertNotNull(chain.getRequest(), "the authorization rules before this filter already decided about it");
		assertEquals(0, asked.get());
	}
}
