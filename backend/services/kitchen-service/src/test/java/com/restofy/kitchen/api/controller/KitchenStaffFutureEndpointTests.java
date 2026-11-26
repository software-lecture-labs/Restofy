package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An endpoint added later must be confirmed with Auth until someone has put it on the reviewed read list, even when its
 * path has the shape of a listed one. The two endpoints here exist only in this test: each is a literal segment where a
 * listed read has an order id, and Spring would send the request to the literal one.
 */
@Import(KitchenStaffFutureEndpointTests.LaterEndpoints.class)
class KitchenStaffFutureEndpointTests extends StaffCookieTestBase {

	@TestConfiguration
	static class LaterEndpoints {

		@RestController
		static class LaterController {

			@GetMapping("/api/v1/kitchen/orders/later-addition")
			String besideTheOrderDetail() {
				return "later";
			}

			@GetMapping("/api/v1/kitchen/orders/later-addition/cancellation-requests")
			String besideTheCancellationHistory() {
				return "later";
			}
		}
	}

	private final String cook = StaffTokens.access("KITCHEN", UUID.randomUUID(), UUID.randomUUID());

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/kitchen/orders/later-addition",
			"/api/v1/kitchen/orders/later-addition/cancellation-requests" })
	void anEndpointShapedLikeAListedReadIsStillConfirmed(String path) throws Exception {
		doReturn(Optional.empty()).when(auth).current(anyString());

		mvc.perform(withCookie(request(HttpMethod.GET, path), cook)).andExpect(status().isUnauthorized());
		mvc.perform(withCookie(request(HttpMethod.HEAD, path), cook)).andExpect(status().isUnauthorized());

		verify(auth, times(2)).current(cook);
	}

	/** The real order detail keeps relying on the local proof, whatever the case of the id's hex digits. */
	@Test
	void anOrderIdIsStillReadWithoutAskingAuth() throws Exception {
		UUID restaurant = UUID.randomUUID();
		UUID orderId = accept(restaurant);
		String token = StaffTokens.access("KITCHEN", restaurant, UUID.randomUUID());
		doReturn(Optional.empty()).when(auth).current(anyString());

		for (String id : new String[] { orderId.toString(), orderId.toString().toUpperCase() }) {
			mvc.perform(withCookie(request(HttpMethod.GET, "/api/v1/kitchen/orders/" + id), token)).andExpect(status().isOk());
			mvc.perform(withCookie(request(HttpMethod.GET, "/api/v1/kitchen/orders/" + id + "/cancellation-requests"), token))
					.andExpect(status().isOk());
		}

		verify(auth, times(0)).current(anyString());
	}
}
