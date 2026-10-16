package com.restofy.auth.api;

import java.util.HashMap;
import java.util.Map;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.RefreshTokenRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.persistence.repository.UserSessionRepository;
import com.restofy.auth.infrastructure.security.AuthCookies;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class StaffAuthHttpTests extends AuthSessionTestSupport {
    private static final String ORIGIN = "http://localhost:5000";
    private static final String PASSWORD = "Http-test-password-01";
    @Autowired private MockMvc mvc;
    @Autowired private StaffUserRepository users;
    @Autowired private UserSessionRepository sessions;
    @Autowired private RefreshTokenRepository tokens;
    @Autowired private StaffPasswords passwords;
    private final Map<String, Cookie> jar = new HashMap<>();

    @BeforeEach
    void setup() {
        tokens.deleteAll();
        sessions.deleteAll();
        users.deleteAll();
        jar.clear();
        users.saveAndFlush(new StaffUser("staff@example.com", passwords.hash(PASSWORD), StaffRole.WAITER));
    }

    @Test
    void cookieLoginSessionActivityRefreshAndLogoutNeverReturnCredentialBodies() throws Exception {
        csrf();
        MvcResult login = mvc.perform(mutation("/login").content(body("staff@example.com", PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.role").value("WAITER")).andExpect(jsonPath("$.access").doesNotExist())
                .andExpect(jsonPath("$.refresh").doesNotExist()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        update(login);
        String access = jar.get(AuthCookies.ACCESS).getValue();
        String refresh = jar.get(AuthCookies.REFRESH).getValue();
        assertThat(login.getResponse().getContentAsString()).doesNotContain(access, refresh, PASSWORD);
        assertThat(login.getResponse().getHeaders("Set-Cookie")).anyMatch(value -> value.startsWith(AuthCookies.ACCESS + "=")
                && value.contains("HttpOnly") && value.contains("Path=/api/v1;") && value.contains("SameSite=Lax") && !value.contains("Domain="));
        assertThat(login.getResponse().getHeaders("Set-Cookie")).anyMatch(value -> value.startsWith(AuthCookies.REFRESH + "=")
                && value.contains("Path=/api/v1/auth;") && value.contains("HttpOnly"));
        var before = sessions.findAll().getFirst().getLastActivityAt();
        mvc.perform(get("/api/v1/auth/session").cookie(cookies())).andExpect(status().isOk());
        assertThat(sessions.findAll().getFirst().getLastActivityAt()).isEqualTo(before);
        mvc.perform(mutation("/activity")).andExpect(status().isNoContent());
        update(mvc.perform(mutation("/refresh")).andExpect(status().isOk()).andReturn());
        assertThat(jar.get(AuthCookies.REFRESH).getValue()).isNotEqualTo(refresh);
        mvc.perform(mutation("/logout")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/session").cookie(cookies())).andExpect(status().isUnauthorized());
        assertThat(sessions.findAll()).allMatch(session -> session.getRevokedAt() != null);
        assertThat(login.getRequest().getSession(false)).isNull();
    }

    @Test
    void csrfRequiresValidHeaderOriginAndCredentialBinding() throws Exception {
        mvc.perform(post("/api/v1/auth/login").header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content(body("staff@example.com", PASSWORD))).andExpect(status().isForbidden());
        csrf();
        mvc.perform(post("/api/v1/auth/login").cookie(cookies()).header("X-CSRF-TOKEN", jar.get(AuthCookies.CSRF).getValue())
                .contentType(MediaType.APPLICATION_JSON).content(body("staff@example.com", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").header("Origin", "https://attacker.example").cookie(cookies())
                .header("X-CSRF-TOKEN", jar.get(AuthCookies.CSRF).getValue()).contentType(MediaType.APPLICATION_JSON)
                .content(body("staff@example.com", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").header("Origin", ORIGIN).cookie(cookies()).header("X-CSRF-TOKEN", "forged")
                .contentType(MediaType.APPLICATION_JSON).content(body("staff@example.com", PASSWORD))).andExpect(status().isForbidden());
        String oldCsrf = jar.get(AuthCookies.CSRF).getValue();
        login("staff@example.com");
        mvc.perform(post("/api/v1/auth/refresh").header("Origin", ORIGIN)
                .cookie(new Cookie(AuthCookies.REFRESH, jar.get(AuthCookies.REFRESH).getValue()), new Cookie(AuthCookies.CSRF, oldCsrf))
                .header("X-CSRF-TOKEN", oldCsrf)).andExpect(status().isForbidden());
    }

    @Test
    void corsUsesExactOriginsAndCredentials() throws Exception {
        mvc.perform(options("/api/v1/auth/login").header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type,X-CSRF-TOKEN")).andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN)).andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mvc.perform(options("/api/v1/auth/login").header("Origin", "http://localhost:5000.attacker.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/auth/login").header("Origin", ORIGIN).header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pendingAdminHasNoBusinessAuthorityOrRefresh() throws Exception {
        users.saveAndFlush(new StaffUser("admin@example.com", passwords.hash(PASSWORD), StaffRole.ADMIN));
        csrf();
        update(mvc.perform(mutation("/login").content(body("admin@example.com", PASSWORD))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MFA_PENDING")).andReturn());
        assertThat(jar).containsKey(AuthCookies.MFA).doesNotContainKeys(AuthCookies.ACCESS, AuthCookies.REFRESH);
        mvc.perform(get("/api/v1/auth/session").cookie(cookies())).andExpect(status().isOk());
        mvc.perform(mutation("/activity")).andExpect(status().isForbidden());
        mvc.perform(mutation("/refresh")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/admin").cookie(cookies())).andExpect(status().isUnauthorized());
        mvc.perform(mutation("/logout")).andExpect(status().isNoContent());
    }

    @Test
    void validationAndCredentialFailuresNeverExposeInput() throws Exception {
        csrf();
        for (String email : new String[]{"staff@example.com", "missing@example.com", "invalid"}) {
            mvc.perform(mutation("/login").content(body(email, "wrong-password"))).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(email))));
        }
        mvc.perform(mutation("/login").content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(mutation("/login").content("{invalid")).andExpect(status().isBadRequest());
    }

    @Test
    void loginLimitCannotBeEvadedWithCaseOrForwardedHeaders() throws Exception {
        csrf();
        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(mutation("/login").content(body("limited@example.com", "wrong-password"))).andExpect(status().isUnauthorized());
        }
        mvc.perform(mutation("/login").header("X-Forwarded-For", "192.0.2.1").header("X-Role", "ADMIN")
                .content(body(" LIMITED@EXAMPLE.COM ", PASSWORD))).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        mvc.perform(mutation("/login").content(body("other@example.com", "wrong-password"))).andExpect(status().isUnauthorized());
    }

    @Test
    void forgedRolesTokensDuplicateCookiesAndExcludedPathsAreDenied() throws Exception {
        mvc.perform(get("/api/v1/auth/session").header("X-Role", "ADMIN").header("X-User-Id", "arbitrary"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(new Cookie(AuthCookies.ACCESS, "forged"))).andExpect(status().isUnauthorized());
        csrf();
        login("staff@example.com");
        mvc.perform(get("/api/v1/auth/session").cookie(new Cookie(AuthCookies.ACCESS, jar.get(AuthCookies.ACCESS).getValue()),
                new Cookie(AuthCookies.ACCESS, "second"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(cookies()).cookie(new Cookie(AuthCookies.MFA, "pending")))
                .andExpect(status().isUnauthorized());
        for (String path : new String[]{"/api/v1/auth/sessions", "/internal/v1/auth/introspect", "/api/v1/auth/register"}) {
            mvc.perform(get(path).cookie(cookies())).andExpect(status().isUnauthorized());
        }
        mvc.perform(mutation("/logout")).andExpect(status().isNoContent());
        jar.clear();
        csrf();
        mvc.perform(mutation("/logout")).andExpect(status().isNoContent());
    }

    @Test
    void kitchenCanLogin() throws Exception {
        users.saveAndFlush(new StaffUser("kitchen@example.com", passwords.hash(PASSWORD), StaffRole.KITCHEN));
        csrf();
        login("kitchen@example.com");
        mvc.perform(get("/api/v1/auth/session").cookie(cookies())).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("KITCHEN"));
    }

    private void login(String email) throws Exception {
        update(mvc.perform(mutation("/login").content(body(email, PASSWORD))).andExpect(status().isOk()).andReturn());
    }
    private void csrf() throws Exception {
        var request = get("/api/v1/auth/csrf").header("Origin", ORIGIN);
        if (!jar.isEmpty()) { request.cookie(cookies()); }
        update(mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.token").isString()).andReturn());
    }
    private MockHttpServletRequestBuilder mutation(String path) {
        return post("/api/v1/auth" + path).header("Origin", ORIGIN).header("X-CSRF-TOKEN", jar.get(AuthCookies.CSRF).getValue())
                .cookie(cookies()).contentType(MediaType.APPLICATION_JSON);
    }
    private Cookie[] cookies() { return jar.values().toArray(Cookie[]::new); }
    private void update(MvcResult result) {
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0) { jar.remove(cookie.getName()); } else { jar.put(cookie.getName(), cookie); }
        }
    }
    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"deviceLabel\":\"Test browser\"}";
    }
}
