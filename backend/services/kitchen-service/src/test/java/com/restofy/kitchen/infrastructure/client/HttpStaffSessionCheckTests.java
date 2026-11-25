package com.restofy.kitchen.infrastructure.client;

import com.restofy.kitchen.infrastructure.security.StaffAuthUnavailableException;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real HTTP adapter against a local stand-in for Auth's session endpoint (plain HTTP here; the HTTPS
 * requirement is enforced by the configuration, tested separately). This is NOT a test against a running Auth.
 */
class HttpStaffSessionCheckTests {

	private static final String TOKEN = "aaa.bbb.ccc";
	private static final String ANSWER = """
			{"status":"AUTHENTICATED","role":"KITCHEN","idleExpiresAt":"2026-11-26T11:00:00Z",
			 "absoluteExpiresAt":"2026-12-03T10:00:00Z","mfaSetupRequired":null,"someFutureField":1}""";

	private final List<Headers> received = new CopyOnWriteArrayList<>();
	private final List<String> paths = new CopyOnWriteArrayList<>();
	private final CountDownLatch release = new CountDownLatch(1);
	private HttpServer server;

	private volatile int status = 200;
	private volatile String contentType = "application/json";
	private volatile String body = ANSWER;
	private volatile boolean stall;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/", exchange -> {
			received.add(exchange.getRequestHeaders());
			paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
			if (stall) {
				try {
					release.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException _) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", contentType);
			if (status / 100 == 3) {
				exchange.getResponseHeaders().add("Location", "/stolen");
			}
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
	}

	@AfterEach
	void stop() {
		release.countDown();
		server.stop(0);
	}

	private HttpStaffSessionCheck check(int port) {
		return new HttpStaffSessionCheck(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/session"),
				Duration.ofSeconds(2), Duration.ofMillis(500));
	}

	private HttpStaffSessionCheck check() {
		return check(server.getAddress().getPort());
	}

	@Test
	void aPositiveAnswerIsReadAndOnlyTheAccessCookieIsSent() {
		var state = check().current(TOKEN).orElseThrow();

		assertEquals("AUTHENTICATED", state.status());
		assertEquals("KITCHEN", state.role());
		assertEquals(Instant.parse("2026-11-26T11:00:00Z"), state.idleExpiresAt());
		assertEquals(Instant.parse("2026-12-03T10:00:00Z"), state.absoluteExpiresAt());
		assertNull(state.mfaSetupRequired());
		assertEquals(List.of("GET /api/v1/auth/session"), paths);
		Headers headers = received.getFirst();
		assertEquals(List.of("restofy_access=" + TOKEN), headers.get("Cookie"));
		assertFalse(headers.containsKey("Authorization"));
		assertFalse(headers.containsKey("Origin"));
	}

	@Test
	void answersAreNeverCached() {
		var check = check();
		assertTrue(check.current(TOKEN).isPresent());
		status = 401;

		assertTrue(check.current(TOKEN).isEmpty(), "a later logout is seen on the very next request");
		assertEquals(2, received.size());
	}

	@Test
	void only401IsARefusal() {
		status = 401;
		body = "";

		assertTrue(check().current(TOKEN).isEmpty());
	}

	@ParameterizedTest
	@ValueSource(ints = { 201, 204, 400, 403, 404, 409, 429, 500, 502, 503, 504 })
	void everyOtherStatusIsUnavailableAndIsNotRetried(int answer) {
		status = answer;
		body = answer == 204 ? "" : "{}";
		var check = check();

		var failure = assertThrows(StaffAuthUnavailableException.class, () -> check.current(TOKEN));

		assertEquals("status-" + answer, failure.getReason());
		assertEquals(1, received.size());
	}

	@ParameterizedTest
	@ValueSource(ints = { 301, 302, 307, 308 })
	void aRedirectIsNotFollowedSoTheCookieNeverReachesAnotherAddress(int answer) {
		status = answer;
		var check = check();

		assertThrows(StaffAuthUnavailableException.class, () -> check.current(TOKEN));

		assertEquals(List.of("GET /api/v1/auth/session"), paths);
	}

	@Test
	void anUnreadableAnswerIsUnavailable() {
		var check = check();
		for (String[] answer : new String[][] { { "application/json", "{\"status\":" }, { "text/html", "<html></html>" },
				{ "application/json", "" }, { "application/json", "null" } }) {
			contentType = answer[0];
			body = answer[1];

			assertThrows(StaffAuthUnavailableException.class, () -> check.current(TOKEN), answer[0] + " " + answer[1]);
		}
	}

	@Test
	void aSlowAuthTimesOutAsUnavailable() {
		stall = true;
		var check = check();
		long started = System.nanoTime();

		var failure = assertThrows(StaffAuthUnavailableException.class, () -> check.current(TOKEN));

		assertEquals("no-reliable-answer", failure.getReason());
		assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
		assertEquals(1, received.size(), "no retry");
	}

	@Test
	void anUnreachableAuthIsUnavailableAndTheFailureCarriesNoCredential() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		var check = check(closedPort);

		var failure = assertThrows(StaffAuthUnavailableException.class, () -> check.current(TOKEN));

		assertEquals("no-reliable-answer", failure.getReason());
		assertFalse(failure.getMessage().contains(TOKEN));
		assertNull(failure.getCause());
	}
}
