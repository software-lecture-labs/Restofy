package com.restofy.mail.infrastructure.security;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods=false)
public class MailSecurityConfiguration {
    public static final String JOB_PATH = "/internal/api/v1/mail/password-reset-jobs";
    @Bean AuthenticationManager authenticationManager() {
        return authentication -> { throw new BadCredentialsException("HTTP authentication unavailable"); };
    }
    @Bean SecurityFilterChain mailSecurityFilterChain(HttpSecurity http, MailServiceTokens tokens) {
        return http.csrf(csrf -> csrf.ignoringRequestMatchers(JOB_PATH))
                .addFilterBefore(new ServiceFilter(tokens), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.POST, JOB_PATH).hasAuthority("MAIL_RESET_SEND")
                        .anyRequest().denyAll())
                .httpBasic(basic -> basic.disable()).formLogin(form -> form.disable()).logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> problem(res, 401))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403))).build();
    }
    public static void problem(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status); response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":" + status + "}");
    }
    private static class ServiceFilter extends OncePerRequestFilter {
        private final MailServiceTokens tokens;
        ServiceFilter(MailServiceTokens tokens) { this.tokens = tokens; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            response.setHeader("Cache-Control", "no-store");
            if (JOB_PATH.equals(request.getRequestURI()) && "POST".equals(request.getMethod())) {
                String authorization = request.getHeader("Authorization");
                try {
                    if (authorization == null || !authorization.startsWith("Bearer ")
                            || java.util.Collections.list(request.getHeaders("Authorization")).size() != 1) {
                        throw new BadCredentialsException("Missing service identity");
                    }
                    var messageId = tokens.verify(authorization.substring(7));
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            messageId, null, List.of(new SimpleGrantedAuthority("MAIL_RESET_SEND"))));
                } catch (BadCredentialsException _) { problem(response, 401); return; }
            }
            chain.doFilter(request, response);
        }
    }
}
