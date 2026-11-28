package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.port.MenuAvailabilityPort;
import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.infrastructure.security.StaffSessionCheck;
import com.restofy.kitchen.support.StaffTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Requests through the REAL security chain with signed access cookies (no authentication fixture). Only the two
 * remote boundaries are replaced: Auth's session answer and Menu. The HTTP adapter to Auth has its own test.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class StaffCookieTestBase {

	static final String KEY = "Idempotency-Key";
	static final String ACCESS = "restofy_access";

	@DynamicPropertySource
	static void staffAuthentication(DynamicPropertyRegistry registry) {
		registry.add("restofy.auth.staff-public-key", StaffTokens::publicKey);
		registry.add("restofy.auth.service-url", () -> "https://auth.kitchen-test.invalid");
		registry.add("restofy.auth.issuer", () -> StaffTokens.ISSUER);
		registry.add("restofy.auth.access-ttl-seconds", () -> "300");
	}

	@Autowired
	MockMvc mvc;
	@Autowired
	AcceptedOrderIngestService ingest;
	@Autowired
	JdbcTemplate jdbc;
	@MockitoBean
	StaffSessionCheck auth;
	@MockitoBean
	MenuAvailabilityPort menu;

	@BeforeEach
	void authConfirmsEverySessionByDefault() {
		when(auth.current(anyString())).thenAnswer(call -> Optional.of(session(roleOf(call.getArgument(0)))));
		when(menu.change(any()))
				.thenReturn(MenuAvailabilityPort.Result.unavailable("MENU_TIMEOUT"));
	}

	static StaffSessionCheck.SessionState session(String role) {
		Instant later = Instant.now().plusSeconds(600);
		return new StaffSessionCheck.SessionState("AUTHENTICATED", role, later, later, null);
	}

	private static String roleOf(String token) {
		byte[] payload = Base64.getUrlDecoder().decode(token.split("\\.")[1]);
		return (String) JsonMapper.shared().readValue(payload, Map.class).get("role");
	}

	static MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder request, String token) {
		return request.cookie(new Cookie(ACCESS, token));
	}

	UUID accept(UUID restaurant) {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));
		return orderId;
	}

	String statusOf(UUID restaurant, UUID orderId) {
		return jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				String.class, restaurant, orderId);
	}

	/** Every row a staff command could create, for "nothing happened" assertions. */
	long writtenRows(UUID restaurant) {
		return jdbc.queryForObject("""
				SELECT (SELECT count(*) FROM waste_records WHERE restaurant_id = ?)
				     + (SELECT count(*) FROM cancellation_requests WHERE restaurant_id = ?)
				     + (SELECT count(*) FROM product_availability_commands WHERE restaurant_id = ?)
				""", Long.class, restaurant, restaurant, restaurant);
	}
}
