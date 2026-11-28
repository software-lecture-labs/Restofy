package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.support.OrderCaller;
import com.restofy.kitchen.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.csrf;
import static com.restofy.kitchen.support.OrderCaller.RESTAURANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff authentication and the Order event receiver switched on together. Each identity works on its own routes only:
 * a staff session opens nothing on Order's path, Order's service credential grants no staff permission, and turning
 * CSRF off for Order's path does not turn it off for staff.
 */
class KitchenStaffAndOrderChainsTests extends StaffCookieTestBase {

	@DynamicPropertySource
	static void openOrderReceiver(DynamicPropertyRegistry registry) {
		OrderCaller.configure(registry);
	}

	private final UUID orderId = UUID.randomUUID();

	private byte[] orderEvent() {
		return OrderCaller.bytes(OrderCaller.accepted(RESTAURANT, orderId, UUID.randomUUID(), 0, "-"));
	}

	private static MockHttpServletRequestBuilder orderPath(byte[] body) {
		return post(OrderCaller.PATH).secure(true).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private int storedOrders() {
		return jdbc.queryForObject("SELECT count(*) FROM kitchen_orders WHERE order_id = ?", Integer.class, orderId);
	}

	private static String staffToken(String role) {
		return StaffTokens.access(role, RESTAURANT, UUID.randomUUID());
	}

	@Test
	void ordersSignedEventNeedsNoStaffSessionAndNoCsrfProof() throws Exception {
		byte[] body = orderEvent();

		mvc.perform(orderPath(body).header(HttpHeaders.AUTHORIZATION, OrderCaller.authorization(RESTAURANT, body)))
				.andExpect(status().isNoContent());

		assertEquals(1, storedOrders());
		verify(auth, never()).current(anyString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"KITCHEN", "ADMIN", "WAITER"})
	void aStaffSessionOpensNothingOnOrdersPath(String role) throws Exception {
		byte[] body = orderEvent();
		String token = staffToken(role);

		mvc.perform(withCookie(orderPath(body), token)).andExpect(status().isUnauthorized());
		mvc.perform(withCookie(orderPath(body), token).with(csrf())).andExpect(status().isUnauthorized());

		assertEquals(0, storedOrders());
		verify(auth, never()).current(anyString());
	}

	@Test
	void aStaffCookieBesideOrdersCredentialIsRefused() throws Exception {
		byte[] body = orderEvent();

		mvc.perform(withCookie(orderPath(body), staffToken("KITCHEN"))
						.header(HttpHeaders.AUTHORIZATION, OrderCaller.authorization(RESTAURANT, body)))
				.andExpect(status().isForbidden());

		assertEquals(0, storedOrders());
	}

	@Test
	void ordersCredentialGrantsNoStaffPermission() throws Exception {
		accept(RESTAURANT);
		UUID waiting = accept(RESTAURANT);
		String credential = OrderCaller.authorization(RESTAURANT, orderEvent());
		String start = "/api/v1/kitchen/orders/" + waiting + "/start-preparation";

		mvc.perform(get("/api/v1/kitchen/orders").secure(true).header(HttpHeaders.AUTHORIZATION, credential))
				.andExpect(status().isUnauthorized());
		// Without the staff CSRF proof the mutation is refused before any identity is read.
		mvc.perform(patch(start).secure(true).header(KEY, UUID.randomUUID().toString())
				.header(HttpHeaders.AUTHORIZATION, credential)).andExpect(status().isForbidden());
		mvc.perform(patch(start).secure(true).header(KEY, UUID.randomUUID().toString())
				.header(HttpHeaders.AUTHORIZATION, credential).with(csrf())).andExpect(status().isUnauthorized());

		assertEquals("WAITING", statusOf(RESTAURANT, waiting));
		verify(auth, never()).current(anyString());
	}

	@Test
	void aStaffChangeStillNeedsItsCsrfProofWhileTheReceiverIsOpen() throws Exception {
		UUID waiting = accept(RESTAURANT);
		String start = "/api/v1/kitchen/orders/" + waiting + "/start-preparation";
		String token = staffToken("KITCHEN");

		mvc.perform(withCookie(patch(start).header(KEY, UUID.randomUUID().toString()), token))
				.andExpect(status().isForbidden());
		assertEquals("WAITING", statusOf(RESTAURANT, waiting));

		mvc.perform(withCookie(patch(start).header(KEY, UUID.randomUUID().toString()), token).with(csrf()))
				.andExpect(status().isOk());
		assertEquals("PREPARING", statusOf(RESTAURANT, waiting));
	}
}
