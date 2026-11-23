package com.restofy.menu.infrastructure.config;

import com.restofy.menu.infrastructure.security.SecurityProblems;
import com.restofy.menu.infrastructure.security.StaffAuthProperties;
import com.restofy.menu.infrastructure.security.StaffSecurity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;

/**
 * Three chains, deny by default.
 *
 * <ol>
 *   <li><b>Public</b> opens exactly {@code GET /api/v1/menu/public/**} (the read-only customer menu; what it may return
 *       is decided by the fail-closed restaurant resolver, not by this rule) and denies every other method there. It
 *       grants no staff authority: nothing on it authenticates anyone.</li>
 *   <li><b>Staff</b> covers every other {@code /api/v1/menu/**} path. A request is authenticated only by Auth's
 *       HttpOnly {@code restofy_access} cookie, proved locally with Auth's public key (see {@code StaffSecurity}); a
 *       mutation also needs the CSRF and Origin proof. Which role may do what, and for which restaurant, is decided
 *       again by the endpoint from the verified actor (ADMIN only).</li>
 *   <li><b>Default</b> opens only the health endpoint and refuses everything else. There is no form login, no Basic
 *       login and no local user on any chain: Auth is the only source of staff identity.</li>
 * </ol>
 *
 * Servlet web applications only: a context without a web layer (such as the restart test) has no HTTP security to
 * configure.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(StaffAuthProperties.class)
class MenuSecurityConfiguration {

    static final String PUBLIC_MENU_PATH = "/api/v1/menu/public/**";
    static final String STAFF_MENU_PATH = "/api/v1/menu/**";

    @Bean
    @Order(1)
    SecurityFilterChain publicMenuChain(HttpSecurity http) {
        http.securityMatcher(PUBLIC_MENU_PATH)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, PUBLIC_MENU_PATH).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain staffMenuChain(HttpSecurity http, StaffAuthProperties auth) {
        http.securityMatcher(STAFF_MENU_PATH);
        StaffSecurity.configure(http, auth, Clock.systemUTC());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultChain(HttpSecurity http) {
        http.authorizeHttpRequests(requests -> requests
                        .requestMatchers(EndpointRequest.to("health")).permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> SecurityProblems.unauthenticated(response))
                        .accessDeniedHandler((request, response, exception) -> SecurityProblems.forbidden(response)));
        return http.build();
    }

    /**
     * Menu has no local users. The empty store only stops Boot from creating a default user with a generated password
     * that would be printed to the log.
     */
    @Bean
    UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
