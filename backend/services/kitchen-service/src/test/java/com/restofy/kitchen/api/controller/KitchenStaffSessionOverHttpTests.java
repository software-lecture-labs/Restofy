package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.infrastructure.client.HttpStaffSessionCheck;
import com.restofy.kitchen.infrastructure.security.StaffSessionCheck;
import com.restofy.kitchen.support.StaffTokens;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.restofy.kitchen.support.KitchenTestAuth.CSRF_PROOF;
import static com.restofy.kitchen.support.KitchenTestAuth.csrf;
import static com.restofy.kitchen.support.OrderMessages.item;
import static com.restofy.kitchen.support.OrderMessages.message;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole way from a staff request to the session call on the wire: the real security chain and the real HTTP client
 * against a stand-in for Auth. Only the address differs from production, which requires HTTPS (checked by the
 * configuration tests); this test speaks plain HTTP on the loopback interface and proves nothing about TLS.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenStaffSessionOverHttpTests {

	private static final Duration TIMEOUT = Duration.ofMillis(700);

	/** What the stand-in answers next. */
	private record Answer(int status, String body, Map<String, String> headers, long delayMillis) {

		static Answer of(int status, String body) {
			return new Answer(status, body, Map.of(), 0);
		}
	}

	private record Received(String method, String path, Map<String, List<String>> headers) {
	}

	private static final List<Received> AUTH_CALLS = new CopyOnWriteArrayList<>();
	private static final List<Received> ELSEWHERE_CALLS = new CopyOnWriteArrayList<>();
	private static volatile Answer answer;
	/** Ends a delayed answer early once its test is over, so no stand-in thread keeps waiting afterwards. */
	private static volatile CountDownLatch release = new CountDownLatch(1);
	private static final HttpServer AUTH = server(AUTH_CALLS, true);
	private static final HttpServer ELSEWHERE = server(ELSEWHERE_CALLS, false);

	@TestConfiguration(proxyBeanMethods = false)
	static class RealClientAgainstTheStandIn {

		@Bean
		@Primary
		StaffSessionCheck sessionCheckAgainstTheStandIn() {
			return new HttpStaffSessionCheck(URI.create(base(AUTH) + "/api/v1/auth/session"), TIMEOUT, TIMEOUT);
		}
	}

	@DynamicPropertySource
	static void staffAuthentication(DynamicPropertyRegistry registry) {
		registry.add("restofy.auth.staff-public-key", StaffTokens::publicKey);
		registry.add("restofy.auth.service-url", () -> "https://auth.kitchen-test.invalid");
		registry.add("restofy.auth.issuer", () -> StaffTokens.ISSUER);
		registry.add("restofy.auth.access-ttl-seconds", () -> "300");
	}

	@Autowired
	private MockMvc mvc;
	@Autowired
	private AcceptedOrderIngestService ingest;
	@Autowired
	private JdbcTemplate jdbc;

	private final UUID restaurant = UUID.randomUUID();
	private final String cook = StaffTokens.access("KITCHEN", restaurant, UUID.randomUUID());

	@BeforeEach
	void freshStandIn() {
		release = new CountDownLatch(1);
		AUTH_CALLS.clear();
		ELSEWHERE_CALLS.clear();
		answer = confirmed("KITCHEN");
	}

	@AfterEach
	void releaseDelayedAnswers() {
		release.countDown();
	}

	@AfterAll
	static void stop() {
		AUTH.stop(0);
		ELSEWHERE.stop(0);
	}

	private static String base(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static HttpServer server(List<Received> calls, boolean isAuth) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			server.createContext("/", exchange -> reply(exchange, calls, isAuth ? answer : confirmed("KITCHEN")));
			server.setExecutor(Executors.newCachedThreadPool(task -> {
				Thread thread = new Thread(task, "auth-stand-in");
				thread.setDaemon(true);
				return thread;
			}));
			server.start();
			return server;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void reply(HttpExchange exchange, List<Received> calls, Answer next) throws IOException {
		calls.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
				Map.copyOf(exchange.getRequestHeaders())));
		try {
			// The delay of a slow Auth: it lasts the full time unless the test has already finished.
			if (next.delayMillis() > 0 && release.await(next.delayMillis(), TimeUnit.MILLISECONDS)) {
				return;
			}
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();
		}
		next.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
		byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(next.status(), body.length == 0 ? -1 : body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static String sessionBody(String status, String role) {
		Instant later = Instant.now().plusSeconds(600);
		return "{\"status\":\"" + status + "\",\"role\":\"" + role + "\",\"idleExpiresAt\":\"" + later
				+ "\",\"absoluteExpiresAt\":\"" + later + "\"}";
	}

	private static Answer confirmed(String role) {
		return Answer.of(200, sessionBody("AUTHENTICATED", role));
	}

	private UUID waitingOrder() {
		UUID orderId = UUID.randomUUID();
		ingest.ingest(message(restaurant, orderId, UUID.randomUUID(), "1", "A-1", 1, UUID.randomUUID(), item("Burger", 1, null)));
		return orderId;
	}

	private String statusOf(UUID orderId) {
		return jdbc.queryForObject("SELECT status FROM kitchen_orders WHERE restaurant_id = ? AND order_id = ?",
				String.class, restaurant, orderId);
	}

	/** A browser sends all of its cookies for the path; only the access cookie may travel on to Auth. */
	private ResultActions startPreparation(UUID orderId) throws Exception {
		return mvc.perform(patch("/api/v1/kitchen/orders/{id}/start-preparation", orderId)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.header(HttpHeaders.AUTHORIZATION, "Bearer something-else")
				.cookie(new Cookie("restofy_access", cook), new Cookie("restofy_refresh", "REFRESH-SECRET"),
						new Cookie("restofy_guest", "GUEST-SECRET"))
				.with(csrf()));
	}

	@Test
	void aStateChangeSendsOnlyTheAccessCookieToTheSessionEndpoint() throws Exception {
		UUID orderId = waitingOrder();

		startPreparation(orderId).andExpect(status().isOk());

		assertEquals("PREPARING", statusOf(orderId));
		assertEquals(1, AUTH_CALLS.size());
		Received call = AUTH_CALLS.getFirst();
		assertEquals("GET /api/v1/auth/session", call.method() + " " + call.path());
		assertEquals(List.of("restofy_access=" + cook), headerValues(call, "Cookie"));
		for (String absent : List.of("Origin", "Authorization", "X-CSRF-TOKEN", "Idempotency-Key")) {
			assertEquals(List.of(), headerValues(call, absent), absent);
		}
		for (String secret : List.of("REFRESH-SECRET", "GUEST-SECRET", CSRF_PROOF)) {
			assertFalse(call.headers().toString().contains(secret), secret);
		}
	}

	private static List<String> headerValues(Received call, String name) {
		return call.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
				.flatMap(entry -> entry.getValue().stream()).toList();
	}

	@Test
	void aReadMakesNoCallAtAll() throws Exception {
		waitingOrder();

		mvc.perform(get("/api/v1/kitchen/orders").cookie(new Cookie("restofy_access", cook)))
				.andExpect(status().isOk());

		assertEquals(0, AUTH_CALLS.size(), AUTH_CALLS::toString);
	}

	static Stream<Answer> unreliableAnswers() {
		Instant later = Instant.now().plusSeconds(600);
		return Stream.of(
				Answer.of(500, "{}"), Answer.of(502, ""), Answer.of(503, sessionBody("AUTHENTICATED", "KITCHEN")),
				Answer.of(403, "{}"), Answer.of(404, ""), Answer.of(429, ""), Answer.of(201, sessionBody("AUTHENTICATED", "KITCHEN")),
				Answer.of(204, ""), Answer.of(200, ""), Answer.of(200, "not json"), Answer.of(200, "{}"), Answer.of(200, "[]"),
				Answer.of(200, "<html>login</html>"),
				Answer.of(200, sessionBody("MFA_PENDING", "KITCHEN")),
				Answer.of(200, sessionBody("AUTHENTICATED", "ADMIN")),
				Answer.of(200, "{\"status\":\"AUTHENTICATED\",\"role\":\"KITCHEN\",\"idleExpiresAt\":\"" + later + "\"}"),
				new Answer(200, sessionBody("AUTHENTICATED", "KITCHEN"), Map.of(), TIMEOUT.toMillis() * 3));
	}

	@ParameterizedTest
	@MethodSource("unreliableAnswers")
	void anAnswerThatProvesNothingIs503AndNothingRuns(Answer unreliable) throws Exception {
		UUID orderId = waitingOrder();
		answer = unreliable;

		startPreparation(orderId).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code")
						.value("AUTH_UNAVAILABLE"));

		assertEquals("WAITING", statusOf(orderId));
		assertEquals(1, AUTH_CALLS.size(), "no retry");
	}

	@ParameterizedTest
	@MethodSource("redirects")
	void aRedirectIsNotFollowedSoTheCookieReachesNoOtherAddress(int code) throws Exception {
		UUID orderId = waitingOrder();
		answer = new Answer(code, "", Map.of("Location", base(ELSEWHERE) + "/api/v1/auth/session"), 0);

		startPreparation(orderId).andExpect(status().isServiceUnavailable())
				.andExpect(header().doesNotExist(HttpHeaders.LOCATION));

		assertEquals("WAITING", statusOf(orderId));
		assertEquals(0, ELSEWHERE_CALLS.size(), ELSEWHERE_CALLS::toString);
	}

	static Stream<Integer> redirects() {
		return Stream.of(301, 302, 303, 307, 308);
	}

	@Test
	void aRefusalIs401AndAnEarlierConfirmationDoesNotCount() throws Exception {
		UUID first = waitingOrder();
		UUID second = waitingOrder();
		// A cookie Auth might set on its answer must neither reach the browser nor travel back with the next question.
		answer = new Answer(200, sessionBody("AUTHENTICATED", "KITCHEN"),
				Map.of("Set-Cookie", "restofy_access=PLANTED; Path=/"), 0);
		startPreparation(first).andExpect(status().isOk()).andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

		answer = Answer.of(401, "");
		startPreparation(second).andExpect(status().isUnauthorized());

		assertEquals("WAITING", statusOf(second));
		assertEquals(2, AUTH_CALLS.size());
		assertEquals(List.of("restofy_access=" + cook), headerValues(AUTH_CALLS.get(1), "Cookie"));
	}
}
