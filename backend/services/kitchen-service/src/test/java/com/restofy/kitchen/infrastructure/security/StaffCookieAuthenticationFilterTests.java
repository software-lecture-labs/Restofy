package com.restofy.kitchen.infrastructure.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffCookieAuthenticationFilterTests {

	@Test
	void anOutageNobodyRendersIsStill503AndTheRequestGoesNoFurther() throws Exception {
		StaffSessionAuthenticator authenticator = mock(StaffSessionAuthenticator.class);
		when(authenticator.authenticate(anyString())).thenThrow(new StaffAuthUnavailableException("status-503"));
		var filter = new StaffCookieAuthenticationFilter(authenticator, request -> true,
				(request, response, handler, error) -> null);
		var request = new MockHttpServletRequest("GET", "/api/v1/kitchen/orders");
		request.setCookies(new Cookie(StaffCookieAuthenticationFilter.ACCESS_COOKIE, "a.b.c"));
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		filter.doFilter(request, response, chain);

		assertEquals(503, response.getStatus());
		assertNull(chain.getRequest(), "the request never reaches the application");
		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}
}
