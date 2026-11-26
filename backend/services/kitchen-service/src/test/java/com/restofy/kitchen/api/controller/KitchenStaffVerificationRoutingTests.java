package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.infrastructure.security.StaffAuthUnavailableException;
import com.restofy.kitchen.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.csrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which staff requests ask Auth about the session. Every kitchen endpoint is classified here after a look at what it
 * does: the reads change nothing and rely on the local token proof, the state changes are confirmed with Auth first.
 * The lists must cover every mapping of the application, so a new endpoint cannot arrive unclassified.
 */
class KitchenStaffVerificationRoutingTests extends StaffCookieTestBase {

	private static final String BASE = "/api/v1/kitchen";

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping mappings;
	@Autowired
	private KitchenPreparationService preparation;

	private final UUID restaurant = UUID.randomUUID();
	private final String cook = StaffTokens.access("KITCHEN", restaurant, UUID.randomUUID());
	private final String admin = StaffTokens.access("ADMIN", restaurant, UUID.randomUUID());
	private final String waiter = StaffTokens.access("WAITER", restaurant, UUID.randomUUID());

	/** The reads, each with the status a permitted caller gets. Closed products ask the Menu service, absent today. */
	private enum Read {
		ORDER_LIST("/orders", "/orders", 200),
		ORDER_DETAIL("/orders/{orderId}", "/orders/{order}", 200),
		CANCELLATION_HISTORY("/orders/{orderId}/cancellation-requests", "/orders/{order}/cancellation-requests", 200),
		CLOSED_PRODUCTS("/products/closed", "/products/closed", 503),
		DAILY_REPORT("/reports/daily", "/reports/daily?date=2026-11-26&timeZone=Europe/Istanbul", 200),
		WASTE_LIST("/waste", "/waste", 200);

		private final String pattern;
		private final String url;
		private final int expected;

		Read(String pattern, String url, int expected) {
			this.pattern = pattern;
			this.url = url;
			this.expected = expected;
		}
	}

	private enum Change {
		START_PREPARATION(HttpMethod.PATCH, "/orders/{orderId}/start-preparation", 200),
		MARK_READY(HttpMethod.PATCH, "/orders/{orderId}/mark-ready", 200),
		REQUEST_CANCELLATION(HttpMethod.POST, "/orders/{orderId}/cancellation-requests", 202),
		CHANGE_AVAILABILITY(HttpMethod.PATCH, "/products/{productId}/availability", 503),
		RECORD_WASTE(HttpMethod.POST, "/waste", 201);

		private final HttpMethod method;
		private final String pattern;
		/** With Auth's confirmation. Availability then reaches the (unavailable) Menu service. */
		private final int expected;

		Change(HttpMethod method, String pattern, int expected) {
			this.method = method;
			this.pattern = pattern;
			this.expected = expected;
		}
	}

	/** An order in the state the command needs. */
	private UUID orderFor(Change change) {
		UUID orderId = accept(restaurant);
		if (change == Change.MARK_READY) {
			preparation.startPreparation(restaurant, UUID.randomUUID(), UUID.randomUUID(), orderId);
		}
		return orderId;
	}

	private static MockHttpServletRequestBuilder command(Change change, UUID orderId) {
		String key = UUID.randomUUID().toString();
		return (switch (change) {
			case START_PREPARATION -> patch(BASE + "/orders/{id}/start-preparation", orderId);
			case MARK_READY -> patch(BASE + "/orders/{id}/mark-ready", orderId);
			case REQUEST_CANCELLATION -> post(BASE + "/orders/{id}/cancellation-requests", orderId)
					.contentType("application/json").content("{\"reasonCode\":\"KITCHEN_ERROR\"}");
			case CHANGE_AVAILABILITY -> patch(BASE + "/products/{id}/availability", UUID.randomUUID())
					.contentType("application/json").content("{\"status\":\"OUT_OF_STOCK\"}");
			case RECORD_WASTE -> post(BASE + "/waste").contentType("application/json").content(
					"{\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":2,\"reasonCode\":\"SPOILAGE\"}");
		}).header(KEY, key);
	}

	/** Everything a command could change: orders and their versions, command records and outgoing events. */
	private String state() {
		return jdbc.queryForObject("""
				SELECT coalesce((SELECT string_agg(order_id || status || version, ',' ORDER BY order_id)
				                 FROM kitchen_orders WHERE restaurant_id = ?), '')
				     || '|' || (SELECT count(*) FROM outbox_events WHERE restaurant_id = ?)
				""", String.class, restaurant, restaurant) + "|" + writtenRows(restaurant);
	}

	private MvcResult read(HttpMethod method, Read read, UUID orderId, String token, int expected) throws Exception {
		String url = BASE + read.url.replace("{order}", orderId.toString());
		return mvc.perform(withCookie(request(method, url), token)).andExpect(status().is(expected)).andReturn();
	}

	@Test
	void everyKitchenMappingIsClassified() {
		Set<String> actual = new TreeSet<>();
		mappings.getHandlerMethods().keySet().forEach(mapping -> mapping.getPathPatternsCondition().getPatternValues()
				.stream().filter(pattern -> pattern.startsWith("/api/v1/kitchen"))
				.forEach(pattern -> mapping.getMethodsCondition().getMethods()
						.forEach(method -> actual.add(method.name() + " " + pattern))));
		Set<String> classified = new TreeSet<>();
		for (Read read : Read.values()) {
			classified.add("GET " + BASE + read.pattern);
		}
		for (Change change : Change.values()) {
			classified.add(change.method.name() + " " + BASE + change.pattern);
		}

		assertEquals(classified, actual);
	}

	@ParameterizedTest
	@EnumSource(Read.class)
	void aReadNeverAsksAuthAndChangesNothing(Read read) throws Exception {
		UUID orderId = accept(restaurant);
		String before = state();

		for (String token : new String[] { cook, admin }) {
			read(HttpMethod.GET, read, orderId, token, read.expected);
			read(HttpMethod.HEAD, read, orderId, token, read.expected);
		}

		verify(auth, never()).current(anyString());
		assertEquals(before, state());
	}

	@ParameterizedTest
	@EnumSource(Read.class)
	void whileAuthIsUnreachableAReadStillFollowsTheRoleAndExpiryRules(Read read) throws Exception {
		// doThrow: when(..) would run the default answer with the matcher's empty placeholder.
		doThrow(new StaffAuthUnavailableException("no-reliable-answer")).when(auth).current(anyString());
		UUID orderId = accept(restaurant);
		Instant issuedLongAgo = Instant.now().minusSeconds(301);
		String expired = StaffTokens.sign(StaffTokens.claims("KITCHEN", restaurant, UUID.randomUUID(), issuedLongAgo));
		var shortLived = StaffTokens.claims("KITCHEN", restaurant, UUID.randomUUID(), Instant.now());
		shortLived.put("exp", Instant.now().getEpochSecond() + 30);

		read(HttpMethod.GET, read, orderId, cook, read.expected);
		read(HttpMethod.GET, read, orderId, admin, read.expected);
		read(HttpMethod.GET, read, orderId, StaffTokens.sign(shortLived), read.expected);
		read(HttpMethod.GET, read, orderId, waiter, 403);
		read(HttpMethod.GET, read, orderId, expired, 401);
		mvc.perform(request(HttpMethod.GET, BASE + read.url.replace("{order}", orderId.toString())))
				.andExpect(status().isUnauthorized());

		verify(auth, never()).current(anyString());
	}

	@Test
	void aReadShowsOnlyTheRestaurantOfTheToken() throws Exception {
		UUID mine = accept(restaurant);
		String outsider = StaffTokens.access("KITCHEN", UUID.randomUUID(), UUID.randomUUID());

		read(HttpMethod.GET, Read.ORDER_DETAIL, mine, outsider, 404);
		read(HttpMethod.GET, Read.CANCELLATION_HISTORY, mine, outsider, 404);
		mvc.perform(withCookie(request(HttpMethod.GET, BASE + "/orders"), outsider)).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));

		verify(auth, never()).current(anyString());
	}

	@ParameterizedTest
	@EnumSource(Change.class)
	void aConfirmedStateChangeAsksAuthExactlyOnce(Change change) throws Exception {
		UUID orderId = orderFor(change);

		mvc.perform(withCookie(command(change, orderId), cook).with(csrf())).andExpect(status().is(change.expected));

		verify(auth, times(1)).current(cook);
	}

	@ParameterizedTest
	@EnumSource(Change.class)
	void aSessionAuthRefusesAppliesNothing(Change change) throws Exception {
		UUID orderId = orderFor(change);
		String before = state();
		when(auth.current(cook)).thenReturn(Optional.empty());

		mvc.perform(withCookie(command(change, orderId), cook).with(csrf())).andExpect(status().isUnauthorized());

		assertEquals(before, state());
		verify(auth, times(1)).current(cook);
		verify(menu, never()).change(any());
	}

	@ParameterizedTest
	@EnumSource(Change.class)
	void aStateChangeAuthCannotAnswerForAppliesNothing(Change change) throws Exception {
		UUID orderId = orderFor(change);
		String before = state();
		when(auth.current(cook)).thenThrow(new StaffAuthUnavailableException("status-503"));

		mvc.perform(withCookie(command(change, orderId), cook).with(csrf())).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));

		assertEquals(before, state());
		verify(menu, never()).change(any());
	}

	/** Whatever can be refused from the request and the token alone is refused without a call to Auth. */
	@ParameterizedTest
	@EnumSource(Change.class)
	void aLocallyRefusedStateChangeNeverReachesAuth(Change change) throws Exception {
		UUID orderId = orderFor(change);
		String before = state();
		String expired = StaffTokens.sign(
				StaffTokens.claims("KITCHEN", restaurant, UUID.randomUUID(), Instant.now().minusSeconds(301)));
		String foreignKey = StaffTokens.sign(StaffTokens.claims("KITCHEN", restaurant, UUID.randomUUID(), Instant.now()),
				StaffTokens.generate().getPrivate());

		mvc.perform(withCookie(command(change, orderId), admin).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(withCookie(command(change, orderId), waiter).with(csrf())).andExpect(status().isForbidden());
		mvc.perform(withCookie(command(change, orderId), expired).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(withCookie(command(change, orderId), foreignKey).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(command(change, orderId).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(withCookie(command(change, orderId), cook)).andExpect(status().isForbidden());

		verify(auth, never()).current(anyString());
		verify(menu, never()).change(any());
		assertEquals(before, state());
	}

	/** Only reviewed endpoints skip the confirmation: anything else under the kitchen path, a GET included, needs it. */
	@Test
	void anEndpointThatIsNotOnTheReviewedListIsConfirmedEvenAsAGet() throws Exception {
		when(auth.current(cook)).thenThrow(new StaffAuthUnavailableException("status-503"));

		for (String url : new String[] { BASE + "/anything-new", BASE + "/orders/x/y/z", BASE + "/reports" }) {
			mvc.perform(withCookie(request(HttpMethod.GET, url), cook)).andExpect(status().isServiceUnavailable());
		}

		verify(auth, times(3)).current(cook);
	}
}
