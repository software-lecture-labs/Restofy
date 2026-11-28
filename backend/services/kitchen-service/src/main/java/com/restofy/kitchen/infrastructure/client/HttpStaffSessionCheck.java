package com.restofy.kitchen.infrastructure.client;

import com.restofy.kitchen.infrastructure.security.StaffAuthUnavailableException;
import com.restofy.kitchen.infrastructure.security.StaffCookieAuthenticationFilter;
import com.restofy.kitchen.infrastructure.security.StaffSessionCheck;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * {@link StaffSessionCheck} against Auth's existing {@code GET /api/v1/auth/session}. Only the access cookie is
 * sent. 200 is read, 401 is a refusal, and everything else (other statuses, redirects, timeouts, connection
 * problems, unreadable bodies) is "unavailable". Redirects are not followed, so the cookie never reaches another
 * address; there is no retry and no cache. This request does not count as staff activity at Auth.
 */
public class HttpStaffSessionCheck implements StaffSessionCheck {

	private static final int OK = 200;
	private static final int UNAUTHORIZED = 401;

	private final RestClient http;
	private final URI sessionUri;

	public HttpStaffSessionCheck(URI sessionUri, Duration connectTimeout, Duration readTimeout) {
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(connectTimeout)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(client);
		requests.setReadTimeout(readTimeout);
		this.http = RestClient.builder().requestFactory(requests).build();
		this.sessionUri = sessionUri;
	}

	@Override
	public Optional<SessionState> current(String accessToken) {
		try {
			return http.get().uri(sessionUri)
					.accept(MediaType.APPLICATION_JSON)
					.header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store")
					.header(HttpHeaders.COOKIE, StaffCookieAuthenticationFilter.ACCESS_COOKIE + "=" + accessToken)
					.exchange((request, response) -> {
						int status = response.getStatusCode().value();
						if (status == UNAUTHORIZED) {
							return Optional.<SessionState>empty();
						}
						if (status != OK) {
							throw new StaffAuthUnavailableException("status-" + status);
						}
						SessionState state = response.bodyTo(SessionState.class);
						if (state == null) {
							throw new StaffAuthUnavailableException("empty-answer");
						}
						return Optional.of(state);
					});
		} catch (RestClientException | InvalidMediaTypeException _) {
			// The cause is not kept: client exceptions can quote the address or the response.
			throw new StaffAuthUnavailableException("no-reliable-answer");
		}
	}
}
