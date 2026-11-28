package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.infrastructure.security.OrderEventSecurityConfiguration;
import com.restofy.kitchen.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static com.restofy.kitchen.support.KitchenTestAuth.csrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * The role matrix of every kitchen endpoint, through the real cookie chain. The list is compared with the routes the
 * application really registers, so a new endpoint fails this test until its access is decided here.
 */
class KitchenEndpointAccessMatrixTests extends StaffCookieTestBase {

	private record Endpoint(HttpMethod method, String path) {
		boolean read() {
			return method == HttpMethod.GET;
		}
	}

	private static final Set<String> EXPECTED = Set.of(
			"GET /api/v1/kitchen/orders",
			"GET /api/v1/kitchen/orders/{orderId}",
			"PATCH /api/v1/kitchen/orders/{orderId}/start-preparation",
			"PATCH /api/v1/kitchen/orders/{orderId}/mark-ready",
			"GET /api/v1/kitchen/orders/{orderId}/cancellation-requests",
			"POST /api/v1/kitchen/orders/{orderId}/cancellation-requests",
			"PATCH /api/v1/kitchen/products/{productId}/availability",
			"GET /api/v1/kitchen/products/closed",
			"GET /api/v1/kitchen/reports/daily",
			"GET /api/v1/kitchen/waste",
			"POST /api/v1/kitchen/waste");

	/**
	 * The one route no staff role belongs to: Order's service identity has its own chain. How it behaves with a staff
	 * session is in {@code KitchenStaffAndOrderChainsTests}.
	 */
	private static final String ORDER_EVENTS = "POST " + OrderEventSecurityConfiguration.PATH;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping mappings;

	private Set<String> registered() {
		Set<String> routes = new TreeSet<>();
		mappings.getHandlerMethods().forEach((info, handler) -> {
			if (handler.getBeanType().getPackageName().startsWith("com.restofy.kitchen")) {
				for (String path : info.getPatternValues()) {
					info.getMethodsCondition().getMethods().forEach(m -> routes.add(m.name() + " " + path));
					assertFalse(info.getMethodsCondition().getMethods().isEmpty(), "a route without a verb: " + path);
				}
			}
		});
		return routes;
	}

	private static MockHttpServletRequestBuilder call(Endpoint endpoint) {
		var builder = request(endpoint.method(), endpoint.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString()));
		return endpoint.read() ? builder : builder.header(KEY, UUID.randomUUID().toString());
	}

	private int statusFor(Endpoint endpoint, String role, boolean proof) throws Exception {
		var builder = call(endpoint);
		if (role != null) {
			builder = withCookie(builder, StaffTokens.access(role, UUID.randomUUID(), UUID.randomUUID()));
		}
		if (proof) {
			builder.with(csrf());
		}
		return mvc.perform(builder).andReturn().getResponse().getStatus();
	}

	private int headStatus(Endpoint endpoint, String role) throws Exception {
		var builder = request(endpoint.method(), endpoint.path().replaceAll("\\{[^}]+}", UUID.randomUUID().toString()));
		if (role != null) {
			builder = withCookie(builder, StaffTokens.access(role, UUID.randomUUID(), UUID.randomUUID()));
		}
		return mvc.perform(builder).andReturn().getResponse().getStatus();
	}

	@Test
	void everyRegisteredKitchenRouteIsCoveredByThisMatrix() {
		Set<String> decided = new TreeSet<>(EXPECTED);
		decided.add(ORDER_EVENTS);

		assertEquals(decided, registered());
	}

	@Test
	void anonymousAndWaiterAreRefusedEverywhereKitchenMayUseEverythingAndAdminOnlyReads() throws Exception {
		for (String route : EXPECTED) {
			String[] parts = route.split(" ");
			Endpoint endpoint = new Endpoint(HttpMethod.valueOf(parts[0]), parts[1]);

			assertEquals(401, statusFor(endpoint, null, !endpoint.read()), "anonymous " + route);
			assertEquals(403, statusFor(endpoint, "WAITER", !endpoint.read()), "WAITER " + route);
			assertAuthorized(statusFor(endpoint, "KITCHEN", !endpoint.read()), "KITCHEN " + route);
			if (endpoint.read()) {
				assertAuthorized(statusFor(endpoint, "ADMIN", false), "ADMIN " + route);
				Endpoint head = new Endpoint(HttpMethod.HEAD, endpoint.path());
				assertEquals(401, headStatus(head, null), "anonymous HEAD " + route);
				assertEquals(403, headStatus(head, "WAITER"), "WAITER HEAD " + route);
				assertAuthorized(headStatus(head, "ADMIN"), "ADMIN HEAD " + route);
			} else {
				assertEquals(403, statusFor(endpoint, "ADMIN", true), "ADMIN " + route);
				assertEquals(403, statusFor(endpoint, "KITCHEN", false), "KITCHEN without CSRF proof " + route);
				assertEquals(403, statusFor(endpoint, null, false), "anonymous without CSRF proof " + route);
			}
		}
	}

	@Test
	void unmappedWriteVerbsStayClosedForAdmin() throws Exception {
		for (HttpMethod method : new HttpMethod[] { HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE }) {
			for (String path : new String[] { "/api/v1/kitchen/orders", "/api/v1/kitchen/reports/daily",
					"/api/v1/kitchen/anything", "/api/v1/kitchen" }) {
				assertEquals(403, statusFor(new Endpoint(method, path), "ADMIN", true), method + " " + path);
			}
		}
	}

	/** Authentication and authorization passed: the answer then depends on the data, never 401 or 403. */
	private static void assertAuthorized(int status, String call) {
		assertFalse(status == 401 || status == 403 || status >= 500 && status != 503, call + " -> " + status);
	}
}
