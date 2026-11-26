package com.restofy.kitchen.infrastructure.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderEventAuthenticationFilterTests {

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	/** If the verifier itself breaks, nothing gets through, and the answer is one the caller retries. */
	@Test
	void anUnexpectedVerifierFailureLetsNothingThroughAndIsAServerError() throws Exception {
		OrderCallerVerifier verifier = mock(OrderCallerVerifier.class);
		when(verifier.verify(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("SECRET-DETAIL"));
		var request = new MockHttpServletRequest("POST", OrderEventSecurityConfiguration.PATH);
		request.setSecure(true);
		request.addHeader("Authorization", "Service token");
		var response = new MockHttpServletResponse();
		var chain = new MockFilterChain();

		new OrderEventAuthenticationFilter(verifier).doFilter(request, response, chain);

		assertEquals(503, response.getStatus());
		assertTrue(response.getContentAsString().contains("AUTHENTICATION_UNAVAILABLE"));
		assertFalse(response.getContentAsString().contains("SECRET-DETAIL"));
		assertNull(chain.getRequest(), "the endpoint was not reached");
		assertNull(SecurityContextHolder.getContext().getAuthentication());
		assertNull(request.getAttribute(VerifiedOrderRequest.ATTRIBUTE));
	}
}
