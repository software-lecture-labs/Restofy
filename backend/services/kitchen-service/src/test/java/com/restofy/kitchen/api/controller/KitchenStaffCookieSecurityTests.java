package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.contract.MenuAvailabilityCommand;
import com.restofy.kitchen.infrastructure.security.StaffAuthUnavailableException;
import com.restofy.kitchen.support.StaffTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.restofy.kitchen.support.KitchenTestAuth.CSRF_PROOF;
import static com.restofy.kitchen.support.KitchenTestAuth.ORIGIN;
import static com.restofy.kitchen.support.KitchenTestAuth.csrf;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff authentication end to end inside Kitchen: cookie, JWT, Auth session, CSRF/Origin, tenant and delegation. */
class KitchenStaffCookieSecurityTests extends StaffCookieTestBase {

	private static final String ORDERS = "/api/v1/kitchen/orders";

	private final UUID restaurant = UUID.randomUUID();
	private final UUID other = UUID.randomUUID();
	private final UUID user = UUID.randomUUID();
	private final String cook = StaffTokens.access("KITCHEN", restaurant, user);

	private static MockHttpServletRequestBuilder startPreparation(UUID orderId) {
		return patch(ORDERS + "/" + orderId + "/start-preparation").header(KEY, UUID.randomUUID().toString());
	}

	/** Identity claims a client may try to smuggle in; none of them may have any effect. */
	private MockHttpServletRequestBuilder withForgedIdentity(MockHttpServletRequestBuilder request, UUID forgedUser) {
		return request.header("X-Restaurant-Id", other.toString()).header("X-Role", "ADMIN")
				.header("X-User-Id", forgedUser.toString()).header("X-Staff-Id", forgedUser.toString())
				.header("X-Requested-By-User-Id", forgedUser.toString()).header("X-On-Behalf-Of", forgedUser.toString())
				.header("X-Service-Name", "kitchen-service").queryParam("restaurantId", other.toString());
	}

	@Test
	void theCookieIdentitySeesAndChangesOnlyItsOwnRestaurantAndIsRecordedAsTheActor() throws Exception {
		UUID mine = accept(restaurant);
		UUID theirs = accept(other);

		mvc.perform(withCookie(get(ORDERS), cook)).andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].orderId", contains(mine.toString())));
		mvc.perform(withCookie(get(ORDERS + "/" + theirs), cook)).andExpect(status().isNotFound());
		mvc.perform(withCookie(startPreparation(theirs), cook).with(csrf())).andExpect(status().isNotFound());
		assertEquals("WAITING", statusOf(other, theirs));

		mvc.perform(withCookie(startPreparation(mine), cook).with(csrf())).andExpect(status().isOk());

		assertEquals("PREPARING", statusOf(restaurant, mine));
		assertEquals(user, jdbc.queryForObject("""
				SELECT h.actor_user_id FROM kitchen_status_history h
				JOIN kitchen_orders o ON o.id = h.kitchen_order_id
				WHERE o.restaurant_id = ? AND o.order_id = ? AND h.to_status = 'PREPARING'
				""", UUID.class, restaurant, mine));
	}

	@Test
	void anotherRestaurantsTokenCannotReachTheOrderEvenWithTheRightIds() throws Exception {
		UUID mine = accept(restaurant);
		String outsider = StaffTokens.access("KITCHEN", other, UUID.randomUUID());

		mvc.perform(withCookie(get(ORDERS + "/" + mine), outsider)).andExpect(status().isNotFound());
		mvc.perform(withCookie(startPreparation(mine), outsider).with(csrf())).andExpect(status().isNotFound());
		mvc.perform(withCookie(get(ORDERS + "/" + mine + "/cancellation-requests"), outsider)).andExpect(status().isNotFound());
		mvc.perform(withCookie(post(ORDERS + "/" + mine + "/cancellation-requests"), outsider).with(csrf())
				.header(KEY, UUID.randomUUID().toString()).contentType("application/json")
				.content("{\"reasonCode\":\"KITCHEN_ERROR\"}")).andExpect(status().isNotFound());

		assertEquals("WAITING", statusOf(restaurant, mine));
		assertEquals(0, writtenRows(restaurant));
	}

	@Test
	void forgedIdentityHeadersAndParametersChangeNeitherTheTenantNorTheRoleNorTheActor() throws Exception {
		UUID mine = accept(restaurant);
		accept(other);
		UUID forgedUser = UUID.randomUUID();
		String admin = StaffTokens.access("ADMIN", restaurant, UUID.randomUUID());

		mvc.perform(withCookie(withForgedIdentity(get(ORDERS), forgedUser), cook)).andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].orderId", contains(mine.toString())));
		// X-Role cannot upgrade an ADMIN (read only) to a writer.
		mvc.perform(withCookie(withForgedIdentity(startPreparation(mine), forgedUser), admin).header("X-Role", "KITCHEN")
				.with(csrf())).andExpect(status().isForbidden());

		String body = """
				{"productId":"%s","quantity":2,"reasonCode":"SPOILAGE","restaurantId":"%s",
				 "recordedByUserId":"%s","userId":"%s","role":"ADMIN"}"""
				.formatted(UUID.randomUUID(), other, forgedUser, forgedUser);
		mvc.perform(withCookie(withForgedIdentity(post("/api/v1/kitchen/waste"), forgedUser), cook).with(csrf())
				.header(KEY, UUID.randomUUID().toString()).contentType("application/json").content(body))
				.andExpect(status().isCreated());

		assertEquals(0, writtenRows(other));
		assertEquals(user, jdbc.queryForObject("SELECT recorded_by_user_id FROM waste_records WHERE restaurant_id = ?",
				UUID.class, restaurant));
		assertEquals("WAITING", statusOf(restaurant, mine));
	}

	@Test
	void theCancellationRequestCarriesTheCookieIdentityNotTheClaimedOne() throws Exception {
		UUID mine = accept(restaurant);
		UUID forgedUser = UUID.randomUUID();

		mvc.perform(withCookie(withForgedIdentity(post(ORDERS + "/" + mine + "/cancellation-requests"), forgedUser), cook)
				.with(csrf()).header(KEY, UUID.randomUUID().toString()).contentType("application/json")
				.content("{\"reasonCode\":\"KITCHEN_ERROR\",\"restaurantId\":\"" + other + "\",\"requestedByUserId\":\""
						+ forgedUser + "\"}")).andExpect(status().isAccepted());

		assertEquals(user, jdbc.queryForObject(
				"SELECT requested_by_user_id FROM cancellation_requests WHERE restaurant_id = ?", UUID.class, restaurant));
		assertEquals(0, writtenRows(other));
	}

	@Test
	void theMenuDelegationNamesTheCookieIdentityAndNeverCarriesTheStaffCredential() throws Exception {
		UUID product = UUID.randomUUID();
		UUID forgedUser = UUID.randomUUID();
		UUID commandId = UUID.randomUUID();

		mvc.perform(withCookie(withForgedIdentity(patch("/api/v1/kitchen/products/" + product + "/availability"), forgedUser),
				cook).with(csrf()).header(KEY, commandId.toString()).contentType("application/json")
				.content("{\"status\":\"OUT_OF_STOCK\",\"restaurantId\":\"" + other + "\",\"requestedByUserId\":\""
						+ forgedUser + "\"}"))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MENU_UNAVAILABLE"));

		var command = ArgumentCaptor.forClass(MenuAvailabilityCommand.class);
		verify(menu).change(command.capture());
		assertEquals(restaurant, command.getValue().restaurantId());
		assertEquals(user, command.getValue().requestedByUserId());
		assertEquals(commandId, command.getValue().commandId());
		assertFalse(command.getValue().toString().contains(cook), "no staff token is delegated downstream");
		assertEquals(1, writtenRows(restaurant), "the command is kept as unconfirmed, never as a success");
		assertEquals("UNCONFIRMED", jdbc.queryForObject(
				"SELECT outcome FROM product_availability_commands WHERE restaurant_id = ?", String.class, restaurant));
	}

	@Test
	void headersAndParametersAloneAuthenticateNobodyAndAuthIsNeverAsked() throws Exception {
		UUID mine = accept(restaurant);
		List<MockHttpServletRequestBuilder> attempts = new ArrayList<>();
		attempts.add(get(ORDERS));
		attempts.add(withForgedIdentity(get(ORDERS), user));
		for (String scheme : List.of("Bearer ", "Staff ", "Service ")) {
			attempts.add(get(ORDERS).header(HttpHeaders.AUTHORIZATION, scheme + cook));
		}
		attempts.add(get(ORDERS).header(ACCESS, cook));
		attempts.add(get(ORDERS).queryParam("access_token", cook).queryParam(ACCESS, cook));
		for (MockHttpServletRequestBuilder attempt : attempts) {
			mvc.perform(attempt).andExpect(status().isUnauthorized());
		}
		// With a correct CSRF proof a write without the cookie is still anonymous.
		mvc.perform(withForgedIdentity(startPreparation(mine), user).header(HttpHeaders.AUTHORIZATION, "Service " + cook)
				.with(csrf())).andExpect(status().isUnauthorized());

		verify(auth, never()).current(anyString());
		assertEquals("WAITING", statusOf(restaurant, mine));
	}

	@Test
	void aServiceCredentialIsNeverAStaffSessionAndOpensNoInternalPath() throws Exception {
		UUID mine = accept(restaurant);
		var claims = StaffTokens.claims("KITCHEN", restaurant, user, Instant.now());
		claims.put("iss", "restofy-order");
		claims.put("sub", "order-service");
		claims.put("aud", List.of("restofy-kitchen"));
		claims.put("token_type", "service");
		claims.put("scope", "kitchen:order-events");
		String service = StaffTokens.sign(claims);
		String internal = "/api/v1/internal/kitchen/order-events";

		mvc.perform(withCookie(get(ORDERS), service)).andExpect(status().isUnauthorized());
		mvc.perform(withCookie(startPreparation(mine), service).with(csrf())).andExpect(status().isUnauthorized());
		mvc.perform(get(ORDERS).header(HttpHeaders.AUTHORIZATION, "Service " + service)).andExpect(status().isUnauthorized());
		mvc.perform(post(internal).header(HttpHeaders.AUTHORIZATION, "Service " + service).with(csrf())
				.contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
		// Staff permission does not open an internal path either.
		mvc.perform(withCookie(post(internal), cook).with(csrf()).contentType("application/json").content("{}"))
				.andExpect(status().isUnauthorized());

		assertEquals("WAITING", statusOf(restaurant, mine));
	}

	@Test
	void onlyTheAccessCookieAuthenticatesAndOnlyWithAStaffAccessToken() throws Exception {
		for (String name : List.of("restofy_refresh", "restofy_mfa", "restofy_guest", "access_token")) {
			mvc.perform(get(ORDERS).cookie(new Cookie(name, cook))).andExpect(status().isUnauthorized());
		}
		var pending = StaffTokens.claims("ADMIN", restaurant, user, Instant.now());
		pending.put("aud", List.of("restofy-auth-mfa"));
		pending.put("token_type", "mfa_pending");
		pending.put("auth_state", "MFA_PENDING");

		for (String value : List.of(StaffTokens.sign(pending), "garbage", "", "a.b.c",
				StaffTokens.altered(Instant.now().minusSeconds(900), claims -> { }),
				StaffTokens.sign(StaffTokens.claims("KITCHEN", restaurant, user, Instant.now()),
						StaffTokens.generate().getPrivate()))) {
			mvc.perform(withCookie(get(ORDERS), value)).andExpect(status().isUnauthorized());
		}
		verify(auth, never()).current(anyString());
	}

	@Test
	void aRepeatedAccessCookieIsAmbiguousAndRefusedEvenIfOneValueIsValid() throws Exception {
		mvc.perform(get(ORDERS).cookie(new Cookie(ACCESS, cook), new Cookie(ACCESS, cook))).andExpect(status().isUnauthorized());
		mvc.perform(get(ORDERS).cookie(new Cookie(ACCESS, cook), new Cookie(ACCESS, "other")))
				.andExpect(status().isUnauthorized());

		verify(auth, never()).current(anyString());
	}

	@Test
	void everyStateChangeAsksAuthAgainSoALogoutStopsTheVeryNextOne() throws Exception {
		UUID first = accept(restaurant);
		UUID second = accept(restaurant);
		UUID third = accept(restaurant);
		mvc.perform(withCookie(startPreparation(first), cook).with(csrf())).andExpect(status().isOk());
		mvc.perform(withCookie(startPreparation(second), cook).with(csrf())).andExpect(status().isOk());
		verify(auth, times(2)).current(cook);

		when(auth.current(cook)).thenReturn(Optional.empty());

		mvc.perform(withCookie(startPreparation(third), cook).with(csrf())).andExpect(status().isUnauthorized());
		assertEquals("WAITING", statusOf(restaurant, third));
		verify(auth, times(3)).current(cook);
		// The documented trade-off: a read still works until the token itself expires.
		mvc.perform(withCookie(get(ORDERS), cook)).andExpect(status().isOk());
		verify(auth, times(3)).current(cook);
	}

	@Test
	void whileAuthCannotAnswerNoStateChangeRunsAndTheServiceRecoversWithoutARememberedResult() throws Exception {
		UUID mine = accept(restaurant);
		when(auth.current(cook)).thenThrow(new StaffAuthUnavailableException("status-503"));

		mvc.perform(withCookie(get(ORDERS), cook)).andExpect(status().isOk());
		mvc.perform(withCookie(startPreparation(mine), cook).with(csrf())).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE")).andExpect(jsonPath("$.retryable").value(true))
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"));
		mvc.perform(withCookie(patch("/api/v1/kitchen/products/" + UUID.randomUUID() + "/availability"), cook).with(csrf())
				.header(KEY, UUID.randomUUID().toString()).contentType("application/json")
				.content("{\"status\":\"OUT_OF_STOCK\"}")).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());

		assertEquals("WAITING", statusOf(restaurant, mine));
		assertEquals(0, writtenRows(restaurant));
		verify(menu, never()).change(any());

		doReturn(Optional.of(session("KITCHEN"))).when(auth).current(cook);
		mvc.perform(withCookie(startPreparation(mine), cook).with(csrf())).andExpect(status().isOk());
	}

	@Test
	void anAuthOutageStays503WhateverResponseTypeTheClientAsksFor() throws Exception {
		UUID mine = accept(restaurant);
		when(auth.current(cook)).thenThrow(new StaffAuthUnavailableException("no-reliable-answer"));

		for (String accept : List.of("image/png", "text/html", "application/xml", ";;")) {
			mvc.perform(withCookie(startPreparation(mine), cook).with(csrf()).header(HttpHeaders.ACCEPT, accept))
					.andExpect(status().isServiceUnavailable());
		}
		assertEquals("WAITING", statusOf(restaurant, mine));
	}

	@Test
	void anAuthAnswerForAnotherRoleIsNotTrusted() throws Exception {
		UUID mine = accept(restaurant);
		when(auth.current(cook)).thenReturn(Optional.of(session("ADMIN")));

		mvc.perform(withCookie(startPreparation(mine), cook).with(csrf())).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
		assertEquals("WAITING", statusOf(restaurant, mine));
	}

	@Test
	void aWaiterIsAKnownIdentityWithoutAnyKitchenPermission() throws Exception {
		UUID mine = accept(restaurant);
		String waiter = StaffTokens.access("WAITER", restaurant, UUID.randomUUID());

		mvc.perform(withCookie(get(ORDERS), waiter)).andExpect(status().isForbidden());
		mvc.perform(withCookie(get(ORDERS + "/" + mine), waiter)).andExpect(status().isForbidden());
		mvc.perform(withCookie(startPreparation(mine), waiter).with(csrf())).andExpect(status().isForbidden());
		assertEquals("WAITING", statusOf(restaurant, mine));
	}

	@Test
	void readsNeedNeitherOriginNorCsrfProofAndNeverSetCookies() throws Exception {
		mvc.perform(withCookie(get(ORDERS), cook)).andExpect(status().isOk())
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"));
	}

	private static Cookie proofCookie(String value) {
		return new Cookie("restofy_csrf", value);
	}

	private static Arguments badProof(String name, Cookie[] cookies, List<String[]> headers) {
		return Arguments.of(name, cookies, headers);
	}

	static Stream<Arguments> badBrowserProofs() {
		String[] origin = { "Origin", ORIGIN };
		String[] token = { "X-CSRF-TOKEN", CSRF_PROOF };
		String changed = CSRF_PROOF.replace('n', 'm');
		String expired = "1000000000." + "n".repeat(43) + "." + "s".repeat(43);
		Cookie[] good = { proofCookie(CSRF_PROOF) };
		return Stream.of(
				badProof("nothing at all", new Cookie[0], List.of()),
				badProof("no Origin", good, List.<String[]>of(token)),
				badProof("foreign Origin", good, List.of(new String[] { "Origin", "https://evil.example" }, token)),
				badProof("null Origin", good, List.of(new String[] { "Origin", "null" }, token)),
				badProof("Origin with a path", good, List.of(new String[] { "Origin", ORIGIN + "/" }, token)),
				badProof("look-alike Origin", good, List.of(new String[] { "Origin", ORIGIN + ".evil.example" }, token)),
				badProof("two Origins", good, List.of(origin, new String[] { "Origin", "https://evil.example" }, token)),
				badProof("only a Referer", good, List.of(new String[] { "Referer", ORIGIN + "/kitchen" }, token)),
				badProof("no CSRF header", good, List.<String[]>of(origin)),
				badProof("no CSRF cookie", new Cookie[0], List.of(origin, token)),
				badProof("header differs from cookie", good, List.of(origin, new String[] { "X-CSRF-TOKEN", changed })),
				badProof("two CSRF headers", good, List.of(origin, token, token)),
				badProof("two CSRF cookies", new Cookie[] { proofCookie(CSRF_PROOF), proofCookie(CSRF_PROOF) },
						List.of(origin, token)),
				badProof("expired proof", new Cookie[] { proofCookie(expired) },
						List.of(origin, new String[] { "X-CSRF-TOKEN", expired })),
				badProof("not Auth's proof shape", new Cookie[] { proofCookie("abc") },
						List.of(origin, new String[] { "X-CSRF-TOKEN", "abc" })),
				badProof("form content type", good,
						List.of(origin, token, new String[] { "Content-Type", "application/x-www-form-urlencoded" })),
				badProof("text content type", good, List.of(origin, token, new String[] { "Content-Type", "text/plain" })),
				badProof("unparsable content type", good, List.of(origin, token, new String[] { "Content-Type", ";;" })));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("badBrowserProofs")
	void aMutationWithoutACompleteBrowserProofIsRefusedBeforeAuthIsAsked(String name, Cookie[] cookies,
			List<String[]> headers) throws Exception {
		UUID mine = accept(restaurant);
		MockHttpServletRequestBuilder request = startPreparation(mine);
		List<Cookie> all = new ArrayList<>(List.of(cookies));
		all.add(new Cookie(ACCESS, cook));
		request.cookie(all.toArray(Cookie[]::new));
		for (String[] pair : headers) {
			request.header(pair[0], pair[1]);
		}

		mvc.perform(request).andExpect(status().isForbidden());

		assertEquals("WAITING", statusOf(restaurant, mine), name);
		verify(auth, never()).current(anyString());
	}

	@Test
	void theCsrfProofCannotBeSentAsARequestParameter() throws Exception {
		UUID mine = accept(restaurant);

		mvc.perform(startPreparation(mine).cookie(new Cookie(ACCESS, cook), proofCookie(CSRF_PROOF))
				.header(HttpHeaders.ORIGIN, ORIGIN).param("_csrf", CSRF_PROOF)).andExpect(status().isForbidden());

		assertEquals("WAITING", statusOf(restaurant, mine));
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void credentialsAndProofsNeverReachTheLogsOrTheResponses(CapturedOutput output) throws Exception {
		UUID mine = accept(restaurant);
		String refused = StaffTokens.access("KITCHEN", restaurant, UUID.randomUUID());
		String broken = StaffTokens.access("KITCHEN", restaurant, UUID.randomUUID());
		when(auth.current(refused)).thenReturn(Optional.empty());
		when(auth.current(broken)).thenThrow(new IllegalStateException("boom " + broken));
		StringBuilder responses = new StringBuilder();

		for (String token : List.of(cook, refused, broken, "not.a.token")) {
			responses.append(mvc.perform(withCookie(get(ORDERS), token)).andReturn().getResponse().getContentAsString());
			responses.append(mvc.perform(withCookie(startPreparation(mine), token).with(csrf())).andReturn()
					.getResponse().getContentAsString());
		}

		String everything = output.getAll() + responses;
		for (String secret : List.of(cook, refused, broken, CSRF_PROOF, cook.substring(cook.lastIndexOf('.') + 1))) {
			assertFalse(everything.contains(secret), "a credential leaked");
		}
	}
}
