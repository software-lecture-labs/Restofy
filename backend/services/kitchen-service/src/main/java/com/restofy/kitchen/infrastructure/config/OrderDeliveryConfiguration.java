package com.restofy.kitchen.infrastructure.config;

import com.restofy.kitchen.application.port.OrderEventSink;
import com.restofy.kitchen.application.service.OutboxDeliveryService;
import com.restofy.kitchen.infrastructure.client.HttpOrderEventSink;
import com.restofy.kitchen.infrastructure.client.OrderRequestSigner;
import com.restofy.kitchen.infrastructure.client.OrderServiceCredentials;
import com.restofy.kitchen.infrastructure.messaging.OrderDeliveryDispatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/**
 * Switches on the delivery of Kitchen's events to the Order service, only with the {@code kitchen-order} profile. Without
 * the profile nothing is sent and the outbox simply keeps the events. With it, start-up fails unless the address is
 * safe, the timeouts fit inside the delivery lease and Kitchen's signing key is configured. The Order service on
 * {@code dev} cannot verify that signature yet (PR #47), so a real environment still cannot use the profile.
 */
@Configuration(proxyBeanMethods = false)
@Profile(OrderDeliveryConfiguration.PROFILE)
@EnableScheduling
public class OrderDeliveryConfiguration {

	@Bean
	OrderServiceCredentials orderRequestSigner(Clock clock, @Value("${KITCHEN_ORDER_SIGNING_KEY_ID:}") String keyId,
			@Value("${KITCHEN_ORDER_SIGNING_PRIVATE_KEY:}") String privateKey) {
		return new OrderRequestSigner(clock, keyId, privateKey);
	}

	public static final String PROFILE = "kitchen-order";

	/** Plain http is only ever allowed to the machine itself, where nothing leaves the host. */
	private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

	@Bean(destroyMethod = "close")
	HttpClient orderHttpClient(@Value("${restofy.rest-client.connect-timeout-ms}") long connectTimeoutMs) {
		return HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(Duration.ofMillis(connectTimeoutMs))
				.build();
	}

	@Bean
	OrderEventSink httpOrderEventSink(HttpClient orderHttpClient, @Value("${ORDER_SERVICE_URL:}") String orderServiceUrl,
			@Value("${restofy.rest-client.connect-timeout-ms}") long connectTimeoutMs,
			@Value("${restofy.rest-client.read-timeout-ms}") long readTimeoutMs, JsonMapper json,
			OrderServiceCredentials orderRequestSigner) {
		URI origin = orderOrigin(orderServiceUrl);
		checkTimeouts(connectTimeoutMs, readTimeoutMs);
		return new HttpOrderEventSink(orderClient(orderHttpClient, origin, Duration.ofMillis(readTimeoutMs)), json,
				orderRequestSigner);
	}

	@Bean
	OrderDeliveryDispatcher orderDeliveryDispatcher(OutboxDeliveryService delivery, OrderEventSink httpOrderEventSink) {
		return new OrderDeliveryDispatcher(delivery, httpOrderEventSink);
	}

	/** The RestClient of the Order adapter. */
	public static RestClient orderClient(HttpClient httpClient, URI origin, Duration readTimeout) {
		var factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(readTimeout);
		return RestClient.builder().requestFactory(factory).baseUrl(origin.toString()).build();
	}

	/** An https origin without path, query or user info; plain http only for a loopback address. */
	static URI orderOrigin(String value) {
		try {
			URI url = new URI(value == null ? "" : value.strip());
			boolean secure = "https".equalsIgnoreCase(url.getScheme())
					|| ("http".equalsIgnoreCase(url.getScheme()) && url.getHost() != null && LOOPBACK_HOSTS.contains(url.getHost()));
			boolean bareOrigin = url.getHost() != null && url.getRawUserInfo() == null && url.getRawQuery() == null
					&& url.getRawFragment() == null && (url.getRawPath().isEmpty() || "/".equals(url.getRawPath()));
			if (secure && bareOrigin) {
				return new URI(url.getScheme(), null, url.getHost(), url.getPort(), null, null, null);
			}
		} catch (URISyntaxException _) {
			// reported below, without echoing the configured value
		}
		throw new IllegalStateException("ORDER_SERVICE_URL must be an HTTPS origin (HTTP is only allowed for a loopback address)");
	}

	/** A call must end well before its lease does, or a second worker would send the same event at the same time. */
	static void checkTimeouts(long connectTimeoutMs, long readTimeoutMs) {
		long lease = OutboxDeliveryService.LEASE.toMillis();
		if (connectTimeoutMs <= 0 || readTimeoutMs <= 0 || connectTimeoutMs >= lease || readTimeoutMs >= lease - connectTimeoutMs) {
			throw new IllegalStateException("REST_CLIENT_CONNECT_TIMEOUT_MS and REST_CLIENT_READ_TIMEOUT_MS must be positive"
					+ " and together shorter than the delivery lease of " + lease + " ms");
		}
	}
}
