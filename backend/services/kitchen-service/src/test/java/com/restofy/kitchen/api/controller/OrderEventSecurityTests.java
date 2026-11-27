package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.support.OrderCaller;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.restofy.kitchen.support.OrderCaller.KEYS;
import static com.restofy.kitchen.support.OrderCaller.KEY_ID;
import static com.restofy.kitchen.support.OrderCaller.RESTAURANT;
import static com.restofy.kitchen.support.OrderCaller.accepted;
import static com.restofy.kitchen.support.OrderCaller.authorization;
import static com.restofy.kitchen.support.OrderCaller.bytes;
import static com.restofy.kitchen.support.OrderCaller.claims;
import static com.restofy.kitchen.support.OrderCaller.sign;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who gets past the security of the Order event path. Every refused request leaves nothing stored. */
@SpringBootTest
@AutoConfigureMockMvc
class OrderEventSecurityTests {

	@DynamicPropertySource
	static void openReceiver(DynamicPropertyRegistry registry) {
		OrderCaller.configure(registry);
	}

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;

	private final UUID orderId = UUID.randomUUID();
	private final byte[] body = bytes(accepted(RESTAURANT, orderId, UUID.randomUUID(), 0, "not"));

	private MockHttpServletRequestBuilder secure(byte[] content) {
		return post(OrderCaller.PATH).secure(true).contentType(MediaType.APPLICATION_JSON).content(content);
	}

	private void assertNothingStored() {
		assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM kitchen_orders WHERE order_id = ?", Integer.class, orderId));
	}

	private void assertUnauthenticated(MockHttpServletRequestBuilder builder) throws Exception {
		mvc.perform(builder).andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Service"));
		assertNothingStored();
	}

	private void assertForbidden(MockHttpServletRequestBuilder builder, String code) throws Exception {
		mvc.perform(builder).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(code));
		assertNothingStored();
	}

	@Test
	void theCorrectlySignedRequestPasses() throws Exception {
		mvc.perform(secure(body).header(HttpHeaders.AUTHORIZATION, authorization(RESTAURANT, body)))
				.andExpect(status().isNoContent());
	}

	private static Arguments claim(String name, Consumer<Map<String, Object>> change) {
		return Arguments.of(name, change);
	}

	static Stream<Arguments> wrongClaims() {
		long now = Instant.now().getEpochSecond();
		Stream<Arguments> missing = Stream.of("iss", "sub", "aud", "jti", "iat", "nbf", "exp", "token_type", "scope",
						"restaurant_id", "method", "target", "body_sha256")
				.map(name -> claim("without " + name, c -> c.remove(name)));
		return Stream.concat(missing, Stream.of(
				claim("other issuer", c -> c.put("iss", "restofy-qr")),
				claim("other subject", c -> c.put("sub", "waiter-service")),
				claim("meant for another service", c -> c.put("aud", "restofy-order")),
				claim("user token", c -> c.put("token_type", "access")),
				claim("other scope", c -> c.put("scope", "table-session:lifecycle")),
				claim("restaurant outside the allowed list", c -> c.put("restaurant_id", UUID.randomUUID().toString())),
				claim("restaurant not canonical", c -> c.put("restaurant_id", RESTAURANT.toString().toUpperCase())),
				claim("restaurant not a string", c -> c.put("restaurant_id", 7)),
				claim("jti not a uuid", c -> c.put("jti", "1")),
				claim("signed for another method", c -> c.put("method", "PUT")),
				claim("signed for another path", c -> c.put("target", "/api/v1/internal/kitchen/other")),
				claim("signed with a query", c -> c.put("target", OrderCaller.PATH + "?a=1")),
				claim("digest in upper case", c -> c.put("body_sha256", ((String) c.get("body_sha256")).toUpperCase())),
				claim("digest too short", c -> c.put("body_sha256", "ab")),
				claim("expired", c -> {
					c.put("iat", now - 60);
					c.put("nbf", now - 60);
					c.put("exp", now - 30);
				}),
				claim("issued in the future", c -> {
					c.put("iat", now + 60);
					c.put("nbf", now + 60);
					c.put("exp", now + 90);
				}),
				claim("living longer than 30 seconds", c -> c.put("exp", (long) c.get("iat") + 31)),
				claim("valid before it was issued", c -> c.put("nbf", (long) c.get("iat") - 1))));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("wrongClaims")
	void aCredentialThatBreaksTheContractIsRefused(String name, Consumer<Map<String, Object>> change) throws Exception {
		Map<String, Object> claims = claims(RESTAURANT, body);
		change.accept(claims);

		assertUnauthenticated(secure(body).header(HttpHeaders.AUTHORIZATION, "Service " + sign(claims, KEY_ID, KEYS)));
	}

	@Test
	void onlyOrdersOwnKeyAndAlgorithmAreAccepted() throws Exception {
		KeyPair stranger = OrderCaller.rsa(2048);
		Map<String, Object> claims = claims(RESTAURANT, body);
		String unsigned = Jwts.builder().header().keyId(KEY_ID).and().claims(claims).compact();
		// The classic confusion attack: the public key used as an HMAC secret.
		String hmac = Jwts.builder().header().keyId(KEY_ID).and().claims(claims)
				.signWith(new SecretKeySpec(KEYS.getPublic().getEncoded(), "HmacSHA256"), Jwts.SIG.HS256).compact();
		String otherRsaAlgorithm = Jwts.builder().header().keyId(KEY_ID).and().claims(claims)
				.signWith(KEYS.getPrivate(), Jwts.SIG.RS512).compact();
		String withoutKeyId = Jwts.builder().claims(claims).signWith(KEYS.getPrivate(), Jwts.SIG.RS256).compact();

		for (String token : new String[] { sign(claims, KEY_ID, stranger), sign(claims, "unknown", KEYS), unsigned, hmac,
				otherRsaAlgorithm, withoutKeyId, "not-a-token", "a".repeat(4097) }) {
			assertUnauthenticated(secure(body).header(HttpHeaders.AUTHORIZATION, "Service " + token));
		}
	}

	@Test
	void otherSchemesAndHeadersGrantNothing() throws Exception {
		String token = authorization(RESTAURANT, body).substring("Service ".length());

		assertUnauthenticated(secure(body));
		assertUnauthenticated(secure(body).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
		assertUnauthenticated(secure(body).header(HttpHeaders.AUTHORIZATION, "Service "));
		assertUnauthenticated(secure(body).header(HttpHeaders.AUTHORIZATION,
				"Basic " + Base64.getEncoder().encodeToString("order:order".getBytes(StandardCharsets.UTF_8))));
		assertUnauthenticated(secure(body).header("X-Restaurant-Id", RESTAURANT.toString()).header("X-Service", "order"));
	}

	/** Only what the server itself sees counts as HTTPS; a forwarded header is anyone's claim. */
	@Test
	void plainHttpIsRefusedEvenWithAValidCredentialAndAForwardedHeader() throws Exception {
		assertForbidden(post(OrderCaller.PATH).contentType(MediaType.APPLICATION_JSON).content(body)
				.header(HttpHeaders.AUTHORIZATION, authorization(RESTAURANT, body))
				.header("X-Forwarded-Proto", "https").header("Forwarded", "proto=https"), "HTTPS_REQUIRED");
	}

	@Test
	void aRequestThatIsNotTheSignedOneIsRefused() throws Exception {
		String credential = authorization(RESTAURANT, body);
		byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("\"quantity\":2", "\"quantity\":9")
				.getBytes(StandardCharsets.UTF_8);

		assertForbidden(secure(tampered).header(HttpHeaders.AUTHORIZATION, credential), "REQUEST_NOT_SIGNED");
		assertForbidden(secure(new byte[0]).header(HttpHeaders.AUTHORIZATION, credential), "REQUEST_NOT_SIGNED");
		assertForbidden(post(OrderCaller.PATH + "?replay=1").secure(true).contentType(MediaType.APPLICATION_JSON)
				.content(body).header(HttpHeaders.AUTHORIZATION, credential), "REQUEST_NOT_SIGNED");
		assertForbidden(secure(body).header(HttpHeaders.AUTHORIZATION, credential)
				.header(HttpHeaders.ORIGIN, "https://example.test"), "REQUEST_NOT_SIGNED");
		assertForbidden(secure(body).header(HttpHeaders.AUTHORIZATION, credential)
				.cookie(new Cookie("session", "x")).header(HttpHeaders.COOKIE, "session=x"), "REQUEST_NOT_SIGNED");
		assertForbidden(secure(body).header(HttpHeaders.AUTHORIZATION, credential)
				.header(HttpHeaders.CONTENT_ENCODING, "gzip"), "REQUEST_NOT_SIGNED");
		assertForbidden(secure(body).header(HttpHeaders.AUTHORIZATION, credential, "Bearer other"), "REQUEST_NOT_SIGNED");
		for (HttpMethod method : new HttpMethod[] { HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE }) {
			assertForbidden(request(method, OrderCaller.PATH).secure(true).contentType(MediaType.APPLICATION_JSON)
					.content(body).header(HttpHeaders.AUTHORIZATION, credential), "REQUEST_NOT_SIGNED");
		}
	}

	@Test
	void aBodyOverTheLimitIsRefusedAndOneAtTheLimitIsRead() throws Exception {
		byte[] tooLarge = new byte[256 * 1024 + 1];
		java.util.Arrays.fill(tooLarge, (byte) ' ');
		mvc.perform(secure(tooLarge).header(HttpHeaders.AUTHORIZATION, authorization(RESTAURANT, tooLarge)))
				.andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("BODY_TOO_LARGE"));

		byte[] atLimit = java.util.Arrays.copyOf(tooLarge, 256 * 1024);
		System.arraycopy(body, 0, atLimit, 0, body.length);
		mvc.perform(secure(atLimit).header(HttpHeaders.AUTHORIZATION, authorization(RESTAURANT, atLimit)))
				.andExpect(status().isNoContent());
	}

	@Test
	void theOrderCredentialOpensNoOtherPath() throws Exception {
		for (String path : new String[] { "/api/v1/kitchen/orders", "/api/v1/internal/kitchen/other",
				OrderCaller.PATH + "/", OrderCaller.PATH + "/extra", "/api/v1/internal/kitchen" }) {
			mvc.perform(post(path).secure(true).contentType(MediaType.APPLICATION_JSON).content(body)
							.header(HttpHeaders.AUTHORIZATION, authorization(RESTAURANT, body)))
					.andExpect(result -> {
						int code = result.getResponse().getStatus();
						assertTrue(code == 401 || code == 403, path + " -> " + code);
					});
		}
		assertNothingStored();
	}

	/** The receiver's chain is stateless: a verified request leaves no session or cookie behind. */
	@Test
	void noSessionOrCookieIsCreated() throws Exception {
		var result = mvc.perform(secure(body).header(HttpHeaders.AUTHORIZATION, authorization(RESTAURANT, body)))
				.andExpect(status().isNoContent()).andReturn();

		assertEquals(0, result.getResponse().getCookies().length);
		assertEquals(null, result.getRequest().getSession(false));
	}
}
