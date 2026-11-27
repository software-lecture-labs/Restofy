package com.restofy.kitchen.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * The security of exactly one internal path, the receiver of Order's events. Every other request stays with the
 * deny-by-default chain of {@link SecurityConfiguration}. The path is not routed by the API gateway.
 *
 * <p>CSRF protection is off for this path only: it has no cookie or session to abuse, and a request that carries a
 * cookie or an Origin header is refused.
 */
@Configuration(proxyBeanMethods = false)
public class OrderEventSecurityConfiguration {

	@SuppressWarnings("java:S1075") // The path is part of Order's signed wire contract; it must not be configurable.
	public static final String PATH = "/api/v1/internal/kitchen/order-events";
	static final String METHOD = "POST";
	static final String AUTHORITY = "SERVICE_ORDER_EVENTS";

	// Only a verified, request-bound service credential gets through; a Cookie or Origin header is rejected, so there is
	// no browser credential that a cross-site request could ride on.
	@SuppressWarnings("java:S4502")
	@Bean
	@Order(1)
	SecurityFilterChain orderEventSecurityFilterChain(HttpSecurity http, OrderCallerVerifier verifier) {
		http
				.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(PATH))
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.addFilterBefore(new OrderEventAuthenticationFilter(verifier), AuthorizationFilter.class)
				.authorizeHttpRequests(a -> a
						.requestMatchers(HttpMethod.POST).hasAuthority(AUTHORITY)
						.anyRequest().denyAll())
				.headers(Customizer.withDefaults());
		return http.build();
	}
}
