package com.restofy.auth.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthSecurityTests.ProbeController.class)
@Import({AuthSecurityConfiguration.class, AuthSecurityTests.MethodFixtures.class})
class AuthSecurityTests {
    @Autowired private MockMvc mvc;
    @Autowired private RoleProbe roles;
    @Autowired private AuthenticationManager authenticationManager;

    @Test
    void rejectsAnonymousAndForgedIdentityHeaders() throws Exception {
        mvc.perform(get("/api/v1/auth/probe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/probe").header("X-Role", "ADMIN")
                        .header("X-User-Id", "admin").header("X-Restaurant-Id", "arbitrary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsHttpBasicAndDoesNotIssueSession() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/probe").with(httpBasic("admin", "test-only")))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        var credentials = UsernamePasswordAuthenticationToken.unauthenticated("admin", "test-only");
        assertThatThrownBy(() -> authenticationManager.authenticate(credentials))
                .isInstanceOf(BadCredentialsException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/api/v1/auth/login", "/api/v1/auth/token"})
    void hasNoUsableLoginOrTokenPath(String path) throws Exception {
        mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void retainsCsrfProtection() throws Exception {
        mvc.perform(post("/api/v1/auth/probe")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void evenSyntheticAdminCannotAccessHttpBusinessPaths() throws Exception {
        mvc.perform(get("/api/v1/auth/probe")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotInvokeRoleProtectedMethods() {
        assertThatThrownBy(() -> roles.admin()).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    @WithMockUser(roles = "WAITER")
    void waiterCannotAcquireAdminOrKitchenRole() {
        assertThat(roles.waiter()).isEqualTo("waiter");
        assertThatThrownBy(() -> roles.admin()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> roles.kitchen()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "KITCHEN")
    void kitchenCannotAcquireAdminOrWaiterRole() {
        assertThat(roles.kitchen()).isEqualTo("kitchen");
        assertThatThrownBy(() -> roles.admin()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> roles.waiter()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void rolesAreNotImplicitlyHierarchical() {
        assertThat(roles.admin()).isEqualTo("admin");
        assertThatThrownBy(() -> roles.waiter()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> roles.kitchen()).isInstanceOf(AccessDeniedException.class);
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/auth/probe")
        String probe() { return "must not be accessible"; }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MethodFixtures {
        @Bean RoleProbe roleProbe() { return new RoleProbe(); }
    }

    static class RoleProbe {
        @PreAuthorize("hasRole('ADMIN')") public String admin() { return "admin"; }
        @PreAuthorize("hasRole('WAITER')") public String waiter() { return "waiter"; }
        @PreAuthorize("hasRole('KITCHEN')") public String kitchen() { return "kitchen"; }
    }
}
