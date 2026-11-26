package com.restofy.kitchen.api.controller;

import com.restofy.kitchen.application.service.AcceptedOrderIngestService;
import com.restofy.kitchen.support.OrderCaller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * The receiver on a real server over real TLS, with a certificate made for this run and verified by the client. This
 * covers what MockMvc cannot: that the connection itself makes the request secure, and what an unexpected error looks
 * like on the wire (the servlet error dispatch is only real here).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderEventHttpsTests {

	private static final String STORE_PASSWORD = UUID.randomUUID().toString();
	private static final Path KEY_STORE = selfSignedKeyStore();

	@DynamicPropertySource
	static void tlsAndOpenReceiver(DynamicPropertyRegistry registry) {
		OrderCaller.configure(registry);
		registry.add("server.ssl.key-store", () -> KEY_STORE.toUri().toString());
		registry.add("server.ssl.key-store-password", () -> STORE_PASSWORD);
		registry.add("server.ssl.key-store-type", () -> "PKCS12");
	}

	@Value("${local.server.port}")
	private int port;

	@MockitoSpyBean
	private AcceptedOrderIngestService ingest;

	private static Path selfSignedKeyStore() {
		try {
			Path directory = Files.createTempDirectory("kitchen-tls");
			Path store = directory.resolve("server.p12");
			store.toFile().deleteOnExit();
			directory.toFile().deleteOnExit();
			Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
					"-genkeypair", "-alias", "kitchen", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
					"-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
					"-keystore", store.toString(), "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD)
					.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			if (!keytool.waitFor(60, TimeUnit.SECONDS) || keytool.exitValue() != 0) {
				throw new IllegalStateException("keytool could not create the test certificate");
			}
			return store;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/** Trusts exactly the test certificate; certificate and host name verification stay on. */
	private static HttpClient client() throws IOException, GeneralSecurityException {
		KeyStore server = KeyStore.getInstance("PKCS12");
		try (InputStream in = Files.newInputStream(KEY_STORE)) {
			server.load(in, STORE_PASSWORD.toCharArray());
		}
		KeyStore trusted = KeyStore.getInstance("PKCS12");
		trusted.load(null, null);
		trusted.setCertificateEntry("kitchen", server.getCertificate("kitchen"));
		TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trust.init(trusted);
		SSLContext tls = SSLContext.getInstance("TLS");
		tls.init(null, trust.getTrustManagers(), null);
		return HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).build();
	}

	private HttpResponse<String> send(byte[] body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + OrderCaller.PATH))
				.header("Content-Type", "application/json")
				.header("Authorization", OrderCaller.authorization(OrderCaller.RESTAURANT, body))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
		try (HttpClient http = client()) {
			return http.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

	private static byte[] event(String note) {
		return OrderCaller.bytes(
				OrderCaller.accepted(OrderCaller.RESTAURANT, UUID.randomUUID(), UUID.randomUUID(), 0, note));
	}

	@Test
	void aSignedEventOverTlsIsConfirmedAndItsRedeliveryToo() throws Exception {
		byte[] body = event("not");

		assertEquals(204, send(body).statusCode());
		assertEquals(204, send(body).statusCode());
	}

	/** 401 would tell Order that its credential is wrong; an unexpected failure must stay a server error. */
	@Test
	void anUnexpectedFailureIsARetryableServerErrorThatLeaksNothing() throws Exception {
		for (RuntimeException failure : new RuntimeException[] { new IllegalStateException("SECRET-DETAIL"),
				new DataAccessResourceFailureException("SECRET-DETAIL") }) {
			doThrow(failure).when(ingest).ingest(any());

			HttpResponse<String> response = send(event("not"));

			assertEquals(503, response.statusCode(), response.body());
			assertTrue(response.body().contains("TEMPORARILY_UNAVAILABLE"), response.body());
			assertFalse(response.body().contains("SECRET-DETAIL"), response.body());
		}
	}

	@Test
	void anUnsignedCallOverTlsIsRefused() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + OrderCaller.PATH))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(event("not"))).build();
		try (HttpClient http = client()) {
			assertEquals(401, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
		}
	}
}
