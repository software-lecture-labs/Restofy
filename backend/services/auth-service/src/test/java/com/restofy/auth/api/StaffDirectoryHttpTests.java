package com.restofy.auth.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.restofy.auth.application.service.StaffDirectoryService;
import com.restofy.auth.application.service.StaffSessionService;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.PasswordResetRepository;
import com.restofy.auth.infrastructure.persistence.repository.RefreshTokenRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.persistence.repository.UserSessionRepository;
import com.restofy.auth.infrastructure.security.AuthCookies;
import com.restofy.auth.infrastructure.security.AuthCsrfTokens;
import com.restofy.auth.infrastructure.security.StaffJwtTokens;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import jakarta.servlet.http.Cookie;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class StaffDirectoryHttpTests extends AuthSessionTestSupport {
    private static final String ROOT = "/api/v1/auth/staff";
    private static final String ORIGIN = "http://localhost:5000";
    private static final String PASSWORD = "Staff-create-test-123";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired private MockMvc mvc;
    @Autowired private StaffUserRepository users;
    @Autowired private UserSessionRepository sessions;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StaffPasswords passwords;
    @Autowired private StaffSessionService auth;
    @Autowired private StaffDirectoryService directory;
    @Autowired private StaffJwtTokens jwt;
    @Autowired private AuthCsrfTokens csrf;
    @Autowired private PasswordResetRepository resets;
    @Autowired private JdbcTemplate jdbc;
    private StaffUser admin;
    private StaffUser waiter;
    private Cookie[] adminCookies;

    @AfterEach
    void cleanResetFixtures() {
        jdbc.update("DELETE FROM password_reset_requests");
    }

    @BeforeEach
    void setup() {
        refreshTokens.deleteAll();
        sessions.deleteAll();
        users.deleteAll();
        String hash = passwords.hash(PASSWORD);
        admin = users.saveAndFlush(new StaffUser("directory-admin@example.com", hash, StaffRole.ADMIN));
        waiter = users.saveAndFlush(new StaffUser("directory-waiter@example.com", hash, StaffRole.WAITER));
        adminCookies = cookiesFor(admin, true);
    }

    @Test
    void adminCreatesReadsUpdatesAndDisablesStaffWithoutLeakingCredentials() throws Exception {
        String created = mvc.perform(mutation(ROOT, adminCookies).content(createBody(" NEW@example.com ", "KITCHEN", PASSWORD)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.role").value("KITCHEN")).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        var payload = JSON.readTree(created);
        UUID id = UUID.fromString(payload.get("id").asString());
        assertThat(passwords.matches(PASSWORD, users.findById(id).orElseThrow().getPasswordHash())).isTrue();
        mvc.perform(get(ROOT + "/" + id).cookie(adminCookies)).andExpect(status().isOk());
        mvc.perform(get(ROOT).cookie(adminCookies).param("size", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(mutation(ROOT + "/" + id, adminCookies)
                .content(updateBody("changed@example.com", "WAITER", false, payload.get("version").asLong())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.email").value("changed@example.com")).andExpect(jsonPath("$.role").value("WAITER"));
        assertThat(created).doesNotContain(PASSWORD, "$2a$", "refresh", "mfa", "token");
        assertThatThrownBy(() -> auth.login("changed@example.com", PASSWORD, null))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void staffChangeRevokesExistingAccessAndRefreshAndCanBeReenabled() throws Exception {
        var grant = auth.login(waiter.getEmailNormalized(), PASSWORD, null);
        var oldIdentity = jwt.verify(grant.access(), false);
        mvc.perform(mutation(ROOT + "/" + waiter.getId(), adminCookies)
                .content(updateBody(waiter.getEmailNormalized(), "KITCHEN", false, waiter.getVersion())))
                .andExpect(status().isOk());
        assertThat(sessions.findByStaffUserId(waiter.getId())).allMatch(session -> session.getRevokedAt() != null);
        assertThat(refreshTokens.findBySessionId(grant.session().getId())).allMatch(token -> token.getRevokedAt() != null);
        assertThatThrownBy(() -> auth.authenticate(oldIdentity))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> auth.refresh(grant.refresh()))
                .isInstanceOf(BadCredentialsException.class);
        StaffUser changed = users.findById(waiter.getId()).orElseThrow();
        mvc.perform(mutation(ROOT + "/" + waiter.getId(), adminCookies)
                .content(updateBody(changed.getEmailNormalized(), "KITCHEN", true, changed.getVersion())))
                .andExpect(status().isOk());
        assertThat(auth.login(changed.getEmailNormalized(), PASSWORD, null).session().getRoleSnapshot()).isEqualTo(StaffRole.KITCHEN);
    }

    @Test
    void rejectsWaiterKitchenPendingMfaAndUnauthenticatedCallers() throws Exception {
        for (StaffRole role : new StaffRole[]{StaffRole.WAITER, StaffRole.KITCHEN}) {
            StaffUser user = users.saveAndFlush(new StaffUser(role.name() + "@example.com", passwords.hash(PASSWORD), role));
            Cookie[] cookies = cookiesFor(user, false);
            mvc.perform(get(ROOT).cookie(cookies)).andExpect(status().isForbidden());
            mvc.perform(mutation(ROOT, cookies).content(createBody("denied@example.com", "WAITER", PASSWORD)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(ROOT).cookie(cookiesFor(admin, false))).andExpect(status().isForbidden());
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT).header("Authorization", "Bearer forged")).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMissingCsrfOriginAndCrossOriginRequests() throws Exception {
        String body = createBody("denied@example.com", "WAITER", PASSWORD);
        mvc.perform(post(ROOT).cookie(adminCookies).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(mutation(ROOT, adminCookies).header("Origin", "https://attacker.example").content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(ROOT).cookie(adminCookies).header("X-CSRF-TOKEN", csrfValue(adminCookies))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    @Test
    void duplicateEmailStaleVersionSelfMutationAndUnknownIdentityAreSafeErrors() throws Exception {
        mvc.perform(mutation(ROOT, adminCookies).content(createBody(waiter.getEmailNormalized(), "WAITER", PASSWORD)))
                .andExpect(status().isConflict()).andExpect(content().string(Matchers.not(
                        Matchers.containsString(waiter.getEmailNormalized()))));
        mvc.perform(mutation(ROOT + "/" + waiter.getId(), adminCookies)
                .content(updateBody(waiter.getEmailNormalized(), "KITCHEN", true, waiter.getVersion() + 1)))
                .andExpect(status().isConflict());
        mvc.perform(mutation(ROOT + "/" + admin.getId(), adminCookies)
                .content(updateBody(admin.getEmailNormalized(), "WAITER", false, admin.getVersion())))
                .andExpect(status().isConflict());
        mvc.perform(get(ROOT + "/" + UUID.randomUUID()).cookie(adminCookies)).andExpect(status().isNotFound());
        mvc.perform(mutation(ROOT + "/" + UUID.randomUUID(), adminCookies)
                .content(updateBody("missing@example.com", "WAITER", true, 0))).andExpect(status().isNotFound());
        assertThat(users.findById(admin.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void invalidEmailPasswordRolePagingAndMissingVersionReturn400() throws Exception {
        for (String body : new String[]{createBody("invalid", "WAITER", PASSWORD),
                createBody("ok@example.com", "WAITER", "short"), createBody("ok@example.com", "WAITER", "ş".repeat(40)),
                createBody("ok@example.com", "SUPER_ADMIN", PASSWORD),
                "{\"email\":\"ok@example.com\",\"role\":\"WAITER\"}"}) {
            mvc.perform(mutation(ROOT, adminCookies).content(body)).andExpect(status().isBadRequest());
        }
        for (String size : new String[]{"0", "101", "bad"}) {
            mvc.perform(get(ROOT).cookie(adminCookies).param("size", size)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(ROOT).cookie(adminCookies).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(ROOT + "/bad-id").cookie(adminCookies)).andExpect(status().isBadRequest());
        mvc.perform(mutation(ROOT + "/" + waiter.getId(), adminCookies)
                .content("{\"email\":\"ok@example.com\",\"role\":\"WAITER\",\"active\":true}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unchangedUpdatePreservesSessionsAndVersionAndDuplicateUpdateRollsBack() throws Exception {
        Cookie[] waiterCookies = cookiesFor(waiter, false);
        mvc.perform(mutation(ROOT + "/" + waiter.getId(), adminCookies)
                .content(updateBody(waiter.getEmailNormalized(), "WAITER", true, waiter.getVersion())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(waiter.getVersion()));
        mvc.perform(get("/api/v1/auth/session").cookie(waiterCookies)).andExpect(status().isOk());
        mvc.perform(mutation(ROOT + "/" + waiter.getId(), adminCookies)
                .content(updateBody(admin.getEmailNormalized(), "KITCHEN", false, waiter.getVersion())))
                .andExpect(status().isConflict());
        StaffUser unchanged = users.findById(waiter.getId()).orElseThrow();
        assertThat(unchanged.getEmailNormalized()).isEqualTo(waiter.getEmailNormalized());
        assertThat(unchanged.isActive()).isTrue();
        mvc.perform(get("/api/v1/auth/session").cookie(waiterCookies)).andExpect(status().isOk());
    }

    @Test
    void twoAdminsCannotDisableOneAnotherConcurrentlyAndLeaveNoActiveAdministrator() throws Exception {
        StaffUser second = users.saveAndFlush(new StaffUser("second-admin@example.com", passwords.hash(PASSWORD), StaffRole.ADMIN));
        Cookie[] secondCookies = cookiesFor(second, true);
        var firstIdentity = identity(adminCookies);
        var secondIdentity = identity(secondCookies);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return tryDisable(firstIdentity, second);
            });
            var other = executor.submit(() -> {
                start.await();
                return tryDisable(secondIdentity, admin);
            });
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), other.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(users.findAll().stream().filter(user -> user.getRole() == StaffRole.ADMIN && user.isActive()).count())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emailChangeInvalidatesOutstandingResetProofs(boolean verified) throws Exception {
        StaffUser target = users.saveAndFlush(new StaffUser("old-admin@example.com", passwords.hash(PASSWORD), StaffRole.ADMIN));
        UUID requestId = UUID.randomUUID();
        String cookieDigest = StaffSessionService.digest("old-reset-cookie");
        resets.create(requestId, target.getId(), cookieDigest, StaffSessionService.digest("old-code"), "tr", Instant.now());
        if (verified) {
            resets.verify(requestId, cookieDigest, Instant.now());
        }
        mvc.perform(mutation(ROOT + "/" + target.getId(), adminCookies)
                .content(updateBody(admin.getEmailNormalized(), "ADMIN", true, target.getVersion())))
                .andExpect(status().isConflict());
        assertThat(resets.find(cookieDigest).orElseThrow().status().name())
                .isEqualTo(verified ? "CODE_VERIFIED" : "CODE_PENDING");
        mvc.perform(mutation(ROOT + "/" + target.getId(), adminCookies)
                .content(updateBody("new-admin@example.com", "ADMIN", true, target.getVersion())))
                .andExpect(status().isOk());
        assertThat(resets.find(cookieDigest).orElseThrow().status().name()).isEqualTo("INVALIDATED");
    }

    private boolean tryDisable(StaffJwtTokens.Identity actor, StaffUser target) {
        try {
            directory.update(actor, target.getId(), target.getEmailNormalized(), StaffRole.ADMIN, false, target.getVersion());
            return true;
        } catch (BadCredentialsException _) {
            return false;
        }
    }

    private StaffJwtTokens.Identity identity(Cookie[] cookies) {
        String access = Arrays.stream(cookies).filter(cookie -> AuthCookies.ACCESS.equals(cookie.getName()))
                .findFirst().orElseThrow().getValue();
        return jwt.verify(access, false);
    }

    private Cookie[] cookiesFor(StaffUser user, boolean completeMfa) {
        var grant = auth.login(user.getEmailNormalized(), PASSWORD, null);
        boolean pending = user.getRole() == StaffRole.ADMIN;
        if (pending && completeMfa) {
            // Fixture only: existing MFA HTTP tests cover actual enrollment/verification.
            grant = auth.completeMfa(jwt.verify(grant.access(), true));
            pending = false;
        }
        String csrfToken = csrf.create(AuthCsrfTokens.binding(grant.refresh(), pending ? grant.access() : null)).getToken();
        Map<String, Cookie> result = new HashMap<>();
        result.put("credential", new Cookie(pending ? AuthCookies.MFA : AuthCookies.ACCESS, grant.access()));
        result.put("csrf", new Cookie(AuthCookies.CSRF, csrfToken));
        if (grant.refresh() != null) {
            result.put("refresh", new Cookie(AuthCookies.REFRESH, grant.refresh()));
        }
        return result.values().toArray(Cookie[]::new);
    }

    private static String csrfValue(Cookie[] cookies) {
        return Arrays.stream(cookies).filter(cookie -> AuthCookies.CSRF.equals(cookie.getName()))
                .findFirst().orElseThrow().getValue();
    }

    private static MockHttpServletRequestBuilder mutation(String path, Cookie[] cookies) {
        return post(path).cookie(cookies).header("Origin", ORIGIN).header("X-CSRF-TOKEN", csrfValue(cookies))
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static String createBody(String email, String role, String password) {
        return JSON.writeValueAsString(Map.of("email", email, "role", role, "password", password));
    }

    private static String updateBody(String email, String role, boolean active, long version) {
        return JSON.writeValueAsString(Map.of("email", email, "role", role, "active", active, "version", version));
    }
}
