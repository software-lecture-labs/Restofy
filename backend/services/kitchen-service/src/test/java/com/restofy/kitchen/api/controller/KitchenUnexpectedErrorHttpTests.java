package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.KitchenOrderQueryService;
import com.restofy.kitchen.application.service.KitchenPreparationService;
import com.restofy.kitchen.infrastructure.security.StaffSessionCheck;
import com.restofy.kitchen.support.KitchenTestAuth;
import com.restofy.kitchen.support.StaffTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Real HTTP over the embedded Tomcat: a failure after successful authentication must keep its own status. MockMvc
 * cannot show this, because it performs no container error dispatch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KitchenUnexpectedErrorHttpTests {

	private static final String INTERNAL_DETAIL = "jdbc:postgresql://internal-db:5432/secret_name";

	@DynamicPropertySource
	static void staffAuthentication(DynamicPropertyRegistry registry) {
		registry.add("restofy.auth.staff-public-key", StaffTokens::publicKey);
		registry.add("restofy.auth.service-url", () -> "https://auth.kitchen-test.invalid");
		registry.add("restofy.auth.issuer", () -> StaffTokens.ISSUER);
		registry.add("restofy.auth.access-ttl-seconds", () -> "300");
	}

	@Value("${local.server.port}")
	private int port;
	@MockitoBean
	private StaffSessionCheck auth;
	@MockitoBean
	private KitchenOrderQueryService queries;
	@MockitoBean
	private KitchenPreparationService preparation;

	private HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
		for (int i = 0; i < headers.length; i += 2) {
			builder.header(headers[i], headers[i + 1]);
		}
		return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** A 401 here would tell a signed-in cook that the session is over although the server broke. */
	@Test
	void anUnexpectedFailureForSignedInStaffIs500WithoutInternalDetails() throws Exception {
		Instant later = Instant.now().plusSeconds(600);
		when(auth.current(anyString()))
				.thenReturn(Optional.of(new StaffSessionCheck.SessionState("AUTHENTICATED", "KITCHEN", later, later, null)));
		when(queries.list(any(), any(), any(), any(), anyInt(), anyInt()))
				.thenThrow(new IllegalStateException(INTERNAL_DETAIL));

		HttpResponse<String> response = get("/api/v1/kitchen/orders", "Cookie",
				"restofy_access=" + StaffTokens.access("KITCHEN", UUID.randomUUID(), UUID.randomUUID()));

		assertEquals(500, response.statusCode(), response.body());
		assertFalse(response.body().contains("secret_name"), response.body());
		assertFalse(response.body().contains("IllegalStateException"), response.body());
	}

	/** A write keeps its method on the error dispatch, which must not be refused there either. */
	@Test
	void anUnexpectedFailureOfAWriteIs500Too() throws Exception {
		Instant later = Instant.now().plusSeconds(600);
		when(auth.current(anyString()))
				.thenReturn(Optional.of(new StaffSessionCheck.SessionState("AUTHENTICATED", "KITCHEN", later, later, null)));
		when(preparation.startPreparation(any(), any(), any(), any())).thenThrow(new IllegalStateException(INTERNAL_DETAIL));
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
						+ "/api/v1/kitchen/orders/" + UUID.randomUUID() + "/start-preparation"))
				.method("PATCH", HttpRequest.BodyPublishers.noBody())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.header("Origin", KitchenTestAuth.ORIGIN)
				.header("X-CSRF-TOKEN", KitchenTestAuth.CSRF_PROOF)
				.header("Cookie", "restofy_access=" + StaffTokens.access("KITCHEN", UUID.randomUUID(), UUID.randomUUID())
						+ "; restofy_csrf=" + KitchenTestAuth.CSRF_PROOF)
				.build();

		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertEquals(500, response.statusCode(), response.body());
		assertFalse(response.body().contains("secret_name"), response.body());
	}

	@Test
	void theErrorPathItselfStaysClosedToDirectCallers() throws Exception {
		assertEquals(401, get("/error").statusCode());
	}
}
