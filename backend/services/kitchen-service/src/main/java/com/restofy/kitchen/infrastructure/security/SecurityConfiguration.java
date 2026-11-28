package com.restofy.kitchen.infrastructure.security;

import com.restofy.kitchen.infrastructure.client.HttpStaffSessionCheck;
import com.restofy.kitchen.infrastructure.config.KitchenAuthProperties;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.time.Clock;

/**
 * Deny by default. Kitchen endpoints need a KITCHEN or ADMIN staff identity; everything else is denied, except the
 * anonymous health endpoint that probes and pre-push rely on (no other actuator endpoint is open).
 *
 * <p>Staff authenticate with Auth's HttpOnly {@code restofy_access} cookie ({@link StaffCookieAuthenticationFilter}):
 * the JWT is proven locally on every request. The reviewed reads ({@link StaffReadEndpoints}) need nothing more;
 * every other request is also confirmed with Auth after authorization ({@link StaffSessionConfirmationFilter}).
 * Every mutation also needs the CSRF and Origin proof of {@link StaffCsrfProtection}. Without a configured Auth
 * public key nothing authenticates and every staff request is 401. Form login, HTTP basic and the generated default
 * user are intentionally absent.
 *
 * <p>No service-to-service identity is accepted: Kitchen exposes no internal endpoint, and a service credential
 * grants no staff permission.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KitchenAuthProperties.class)
public class SecurityConfiguration {
	private static final String KITCHEN_PATH = "/api/v1/kitchen/**";
	private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);

	@Bean
	StaffSessionCheck staffSessionCheck(KitchenAuthProperties auth) {
		if (!auth.staffAuthenticationEnabled()) {
			LOG.warn("Kitchen staff authentication is not configured: every staff request will be rejected with 401");
			// Never reached while the token verifier is off; it still fails closed.
			return accessToken -> {
				throw new StaffAuthUnavailableException("not-configured");
			};
		}
		return new HttpStaffSessionCheck(auth.sessionUri(), auth.connectTimeout(), auth.readTimeout());
	}

	@Bean
	SecurityFilterChain kitchenSecurityFilterChain(HttpSecurity http, KitchenAuthProperties auth,
			StaffSessionCheck sessions, Clock clock,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
		StaffCsrfProtection csrf = new StaffCsrfProtection(auth.allowedOrigins(), clock);
		var authenticator = new StaffSessionAuthenticator(new StaffTokenVerifier(auth, clock), sessions, clock);
		var staffRoutes = PathPatternRequestMatcher.withDefaults().matcher(KITCHEN_PATH);
		http
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.requestCache(cache -> cache.disable())
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(csrf))
				// After the CSRF filter: a mutation without proof is refused before its cookie is even read.
				.addFilterBefore(new StaffCookieAuthenticationFilter(authenticator, staffRoutes),
						AnonymousAuthenticationFilter.class)
				// After authorization: Auth is asked only about a request that would otherwise be carried out.
				.addFilterAfter(new StaffSessionConfirmationFilter(authenticator, staffRoutes,
						StaffReadEndpoints.matcher(), errors), AuthorizationFilter.class)
				.exceptionHandling(e -> e
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
						// A plain status: sendError would dispatch to /error and could turn the 403 into another answer.
						.accessDeniedHandler((request, response, denied) -> response.setStatus(HttpServletResponse.SC_FORBIDDEN)))
				.authorizeHttpRequests(a -> a
						// The container's own error rendering of an already authorized request. Without this a server
						// failure would be reported as 401. A direct call to /error is not an ERROR dispatch: still closed.
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						// Only the health endpoint (and its liveness/readiness groups) is anonymous, as in the other
						// services' default chain; details stay hidden (management.endpoint.health.show-details=never).
						.requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
						// Reads: KITCHEN and ADMIN. Every other method (state changes) is KITCHEN only; ADMIN never writes.
						.requestMatchers(HttpMethod.GET, KITCHEN_PATH)
						.hasAnyRole(StaffRole.KITCHEN.name(), StaffRole.ADMIN.name())
						.requestMatchers(HttpMethod.HEAD, KITCHEN_PATH)
						.hasAnyRole(StaffRole.KITCHEN.name(), StaffRole.ADMIN.name())
						.requestMatchers(KITCHEN_PATH).hasRole(StaffRole.KITCHEN.name())
						.anyRequest().denyAll())
				.headers(Customizer.withDefaults());
		return http.build();
	}

	/** An empty user store: prevents Spring Boot from creating a generated default user/password. */
	@Bean
	UserDetailsService noUsers() {
		return new InMemoryUserDetailsManager();
	}
}
