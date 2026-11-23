package com.restofy.menu.infrastructure.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.time.Clock;

/**
 * Builds the staff part of Menu's security: the Auth cookie authentication, the CSRF and Origin proof of mutations and
 * the 401 / 403 answers, on the chain the caller selected. The pieces themselves stay package-private so nothing else
 * can build an actor or switch the proof off.
 *
 * <p>The chain is stateless and has no form login, Basic login, logout, request cache or session: the only way in is a
 * token Auth signed. Every request needs authentication; the endpoint then decides which role may do what.
 */
public final class StaffSecurity {

    private StaffSecurity() { }

    public static void configure(HttpSecurity http, StaffAuthProperties auth, Clock clock) {
        StaffCsrfProtection csrf = new StaffCsrfProtection(auth.originSet(), clock);
        StaffCookieAuthenticationFilter authentication = new StaffCookieAuthenticationFilter(new StaffJwtVerifier(auth, clock));
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(csrf))
                .addFilterBefore(authentication, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> SecurityProblems.unauthenticated(response))
                        .accessDeniedHandler((request, response, exception) -> SecurityProblems.forbidden(response)));
    }
}
