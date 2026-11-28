package com.restofy.auth.api;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.domain.policy.SessionPolicy;
import com.restofy.auth.infrastructure.persistence.repository.StaffMfaRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.AuthCookies;
import com.restofy.auth.infrastructure.security.MfaSecretCipher;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.infrastructure.security.TotpVerifier;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import com.restofy.auth.support.MutableAuthClock;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Self-service password change, ADMIN-issued temporary passwords and the PASSWORD_PENDING session over HTTP. Its own
 * Spring context stays cached for the whole run, so it keeps a small pool against the shared test database.
 */
@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=2"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(StaffDirectoryHttpTests.TimeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PasswordChangeHttpTests extends AuthSessionTestSupport {
    private static final String ORIGIN = "http://localhost:5000";
    private static final String STAFF = "/api/v1/auth/staff";
    private static final String CHANGE = "/api/v1/auth/password/change";
    private static final String SESSION = "/api/v1/auth/session";
    private static final String PASSWORD = "Staff-http-password-01";
    private static final String TEMPORARY = "Temporary-pass-0042";
    private static final String CHOSEN = "Chosen-by-member-0042";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StaffUserRepository users;
    @Autowired private StaffMfaRepository records;
    @Autowired private StaffPasswords passwords;
    @Autowired private MfaSecretCipher cipher;
    @Autowired private TotpVerifier totp;
    @Autowired private MutableAuthClock clock;
    private Client admin;

    @BeforeEach
    void setup() throws Exception {
        clean();
        clock.reset();
        admin = signIn(member("admin@example.com", StaffRole.ADMIN), PASSWORD);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM staff_revocation_targets");
        jdbc.update("DELETE FROM staff_revocation_operations");
        jdbc.update("DELETE FROM mfa_recovery_codes");
        jdbc.update("DELETE FROM staff_mfa");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM user_sessions");
        jdbc.update("DELETE FROM staff_users");
        jdbc.update("DELETE FROM rate_limits");
    }

    @Test
    void aNewMemberMustChooseTheirOwnPasswordBeforeTheSessionIsUsable() throws Exception {
        admin.send(post(STAFF), "{\"email\":\"waiter@example.com\",\"password\":\"" + TEMPORARY + "\",\"role\":\"WAITER\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.passwordChangeRequired").value(true));

        Client waiter = new Client();
        waiter.csrf();
        MvcResult login = waiter.send(post("/api/v1/auth/login"), login("waiter@example.com", TEMPORARY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PASSWORD_PENDING")).andReturn();
        waiter.update(login);
        assertThat(waiter.jar).containsKey(AuthCookies.MFA).doesNotContainKeys(AuthCookies.ACCESS, AuthCookies.REFRESH);
        assertThat(login.getResponse().getCookie(AuthCookies.MFA).getPath()).isEqualTo("/api/v1/auth");

        // Only its own state, the password change and logout: nothing a usable session may do.
        waiter.perform(get(SESSION)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PASSWORD_PENDING"));
        waiter.send(post("/api/v1/auth/activity"), "").andExpect(status().isForbidden());
        waiter.send(post("/api/v1/auth/refresh"), "").andExpect(status().isUnauthorized());
        waiter.send(post("/api/v1/auth/mfa/enroll"), "").andExpect(status().isForbidden());
        // The pending token is not an access token, even when a client puts it in the access cookie.
        Cookie forged = new Cookie(AuthCookies.ACCESS, waiter.jar.get(AuthCookies.MFA).getValue());
        mvc.perform(get(SESSION).cookie(forged)).andExpect(status().isUnauthorized());

        waiter.send(post(CHANGE), change("Wrong-password-0000", CHOSEN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("CURRENT_PASSWORD_REJECTED"));
        waiter.send(post(CHANGE), change(TEMPORARY, "short"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("WEAK_PASSWORD"));
        waiter.send(post(CHANGE), change(TEMPORARY, TEMPORARY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("SAME_PASSWORD"));

        MvcResult changed = waiter.send(post(CHANGE), change(TEMPORARY, CHOSEN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andExpect(content().string(not(containsString(CHOSEN)))).andReturn();
        waiter.update(changed);
        assertThat(waiter.jar).containsKeys(AuthCookies.ACCESS, AuthCookies.REFRESH).doesNotContainKey(AuthCookies.MFA);
        waiter.send(post("/api/v1/auth/activity"), "").andExpect(status().isNoContent());
        admin.perform(get(STAFF)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.email=='waiter@example.com')].passwordChangeRequired").value(false));

        Client later = new Client();
        later.csrf();
        later.send(post("/api/v1/auth/login"), login("waiter@example.com", TEMPORARY)).andExpect(status().isUnauthorized());
        later.send(post("/api/v1/auth/login"), login("waiter@example.com", CHOSEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHENTICATED"));
    }

    @Test
    void aPendingPasswordSessionExpiresQuickly() throws Exception {
        UUID waiter = temporaryMember("waiter@example.com", StaffRole.WAITER);
        Client client = new Client();
        client.csrf();
        client.update(client.send(post("/api/v1/auth/login"), login("waiter@example.com", TEMPORARY))
                .andExpect(status().isOk()).andReturn());
        clock.advance(SessionPolicy.PASSWORD_PENDING_TTL.toSeconds());
        client.perform(get(SESSION)).andExpect(status().isUnauthorized());
        client.send(post(CHANGE), change(TEMPORARY, CHOSEN)).andExpect(status().isUnauthorized());
        assertThat(users.findById(waiter).orElseThrow().isPasswordChangeRequired()).isTrue();
    }

    @Test
    void anAdministratorCreatedByAnotherPassesMfaBeforeChoosingAPassword() throws Exception {
        admin.send(post(STAFF), "{\"email\":\"second@example.com\",\"password\":\"" + TEMPORARY + "\",\"role\":\"ADMIN\""
                + confirmation(PASSWORD, nextCode(admin))).andExpect(status().isCreated());
        UUID second = users.findByEmailNormalized("second@example.com").orElseThrow().getId();

        Client client = new Client();
        client.csrf();
        client.update(client.send(post("/api/v1/auth/login"), login("second@example.com", TEMPORARY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MFA_PENDING")).andReturn());
        client.send(post(CHANGE), change(TEMPORARY, CHOSEN)).andExpect(status().isForbidden());

        client.send(post("/api/v1/auth/mfa/enroll"), "").andExpect(status().isOk());
        MvcResult enrolled = client.send(post("/api/v1/auth/mfa/enroll/confirm"), "{\"code\":\"" + code(second) + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.session.status").value("PASSWORD_PENDING"))
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10)).andReturn();
        client.update(enrolled);
        assertThat(client.jar).containsKey(AuthCookies.MFA).doesNotContainKeys(AuthCookies.ACCESS, AuthCookies.REFRESH);
        client.perform(get(STAFF)).andExpect(status().isForbidden());

        client.update(client.send(post(CHANGE), change(TEMPORARY, CHOSEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHENTICATED")).andReturn());
        client.perform(get(STAFF)).andExpect(status().isOk());
    }

    @Test
    void aTemporaryPasswordEndsTheMembersSessionsAndMustBeReplaced(CapturedOutput output) throws Exception {
        UUID waiterId = member("waiter@example.com", StaffRole.WAITER);
        Client waiter = signIn(waiterId, PASSWORD);

        admin.send(post(STAFF + "/" + waiterId + "/password"), temporary(TEMPORARY, 0))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true)).andExpect(jsonPath("$.version").value(1))
                .andExpect(content().string(not(containsString(TEMPORARY))));
        waiter.perform(get(SESSION)).andExpect(status().isUnauthorized());
        admin.send(post(STAFF + "/" + waiterId + "/password"), temporary(TEMPORARY, 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("STALE_VERSION"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM staff_revocation_operations
                WHERE user_id = ? AND action = 'ALL' AND completion_policy = 'LOCAL_FIRST'
                """, Long.class, waiterId)).isEqualTo(1);

        Client fresh = new Client();
        fresh.csrf();
        fresh.send(post("/api/v1/auth/login"), login("waiter@example.com", PASSWORD)).andExpect(status().isUnauthorized());
        fresh.update(fresh.send(post("/api/v1/auth/login"), login("waiter@example.com", TEMPORARY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PASSWORD_PENDING")).andReturn());
        fresh.update(fresh.send(post(CHANGE), change(TEMPORARY, CHOSEN)).andExpect(status().isOk()).andReturn());

        assertThat(output).contains("staff_event=TEMPORARY_PASSWORD_ASSIGNED actor_id=" + admin.userId
                + " target_id=" + waiterId).contains("password_event=CHANGED user_id=" + waiterId + " required=true")
                .doesNotContain(TEMPORARY).doesNotContain(CHOSEN);
    }

    @Test
    void temporaryPasswordsAreOnlyForActiveNonAdministratorMembers() throws Exception {
        UUID other = member("other-admin@example.com", StaffRole.ADMIN);
        UUID kitchen = member("kitchen@example.com", StaffRole.KITCHEN);
        admin.send(post(STAFF + "/" + other + "/password"), temporary(TEMPORARY, 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("ADMINISTRATOR_TARGET"));
        admin.send(post(STAFF + "/" + admin.userId + "/password"), temporary(TEMPORARY, 0))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SELF_CHANGE"));
        admin.send(post(STAFF + "/" + UUID.randomUUID() + "/password"), temporary(TEMPORARY, 0))
                .andExpect(status().isNotFound());
        admin.send(post(STAFF + "/" + kitchen + "/password"), temporary("short", 0)).andExpect(status().isBadRequest());
        admin.send(post(STAFF + "/" + kitchen + "/password"), "{\"password\":\"" + TEMPORARY + "\"}")
                .andExpect(status().isBadRequest());
        jdbc.update("UPDATE staff_users SET is_active = false, version = version + 1 WHERE id = ?", kitchen);
        admin.send(post(STAFF + "/" + kitchen + "/password"), temporary(TEMPORARY, 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_ACTIVE"));

        Client waiter = signIn(member("waiter@example.com", StaffRole.WAITER), PASSWORD);
        waiter.send(post(STAFF + "/" + kitchen + "/password"), temporary(TEMPORARY, 1)).andExpect(status().isForbidden());
        Client noCsrf = new Client(admin);
        noCsrf.jar.remove(AuthCookies.CSRF);
        noCsrf.send(post(STAFF + "/" + kitchen + "/password"), temporary(TEMPORARY, 1)).andExpect(status().isForbidden());
        assertThat(users.findById(other).orElseThrow().isPasswordChangeRequired()).isFalse();
        assertThat(passwords.matches(PASSWORD, users.findById(other).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void changingTheOwnPasswordEndsOnlyTheOtherSessions() throws Exception {
        UUID waiterId = member("waiter@example.com", StaffRole.WAITER);
        Client phone = signIn(waiterId, PASSWORD);
        Client tablet = signIn(waiterId, PASSWORD);

        MvcResult changed = phone.send(post(CHANGE), change(PASSWORD, CHOSEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHENTICATED")).andReturn();
        assertThat(changed.getResponse().getCookies()).isEmpty();
        phone.send(post("/api/v1/auth/activity"), "").andExpect(status().isNoContent());
        tablet.perform(get(SESSION)).andExpect(status().isUnauthorized());
        assertThat(passwords.matches(CHOSEN, users.findById(waiterId).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void passwordChangesAreRateLimitedPerSession() throws Exception {
        UUID waiterId = member("waiter@example.com", StaffRole.WAITER);
        Client waiter = signIn(waiterId, PASSWORD);
        for (int attempt = 0; attempt < 5; attempt++) {
            waiter.send(post(CHANGE), change("Wrong-password-0000", CHOSEN)).andExpect(status().isBadRequest());
        }
        waiter.send(post(CHANGE), change(PASSWORD, CHOSEN))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        waiter.perform(get(SESSION)).andExpect(status().isOk());

        // A temporary password ends that session; the forced change in the next one is not blocked by the old count.
        admin.send(post(STAFF + "/" + waiterId + "/password"), temporary(TEMPORARY, 0)).andExpect(status().isOk());
        Client next = new Client();
        next.csrf();
        next.update(next.send(post("/api/v1/auth/login"), login("waiter@example.com", TEMPORARY))
                .andExpect(status().isOk()).andReturn());
        next.send(post(CHANGE), change(TEMPORARY, CHOSEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHENTICATED"));
    }

    @Test
    void anonymousCallersAndForeignOriginsCannotChangeAPassword() throws Exception {
        Client anonymous = new Client();
        anonymous.csrf();
        anonymous.send(post(CHANGE), change(PASSWORD, CHOSEN)).andExpect(status().isUnauthorized());
        Client waiter = signIn(member("waiter@example.com", StaffRole.WAITER), PASSWORD);
        waiter.perform(post(CHANGE).header("Origin", "https://evil.example").contentType(MediaType.APPLICATION_JSON)
                .content(change(PASSWORD, CHOSEN))).andExpect(status().isForbidden());
    }

    private String nextCode(Client client) {
        clock.advance(30);
        return code(client.userId);
    }

    private String code(UUID userId) {
        byte[] secret = cipher.decrypt(userId, records.findByStaffUserId(userId).orElseThrow().getEncryptedTotpSecret());
        return totp.code(secret, clock.instant().getEpochSecond() / 30, 6);
    }

    private static String confirmation(String password, String totpCode) {
        return ",\"confirmation\":{\"password\":\"" + password + "\",\"totpCode\":\"" + totpCode + "\"}}";
    }

    private static String login(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String change(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    private static String temporary(String password, long version) {
        return "{\"password\":\"" + password + "\",\"version\":" + version + "}";
    }

    private UUID member(String email, StaffRole role) {
        return users.saveAndFlush(new StaffUser(email, passwords.hash(PASSWORD), role)).getId();
    }

    private UUID temporaryMember(String email, StaffRole role) {
        StaffUser user = new StaffUser(email, passwords.hash(TEMPORARY), role);
        user.requirePasswordChange();
        return users.saveAndFlush(user).getId();
    }

    private Client signIn(UUID userId, String password) throws Exception {
        StaffUser user = users.findById(userId).orElseThrow();
        Client client = new Client(userId);
        client.csrf();
        client.update(client.send(post("/api/v1/auth/login"), login(user.getEmailNormalized(), password))
                .andExpect(status().isOk()).andReturn());
        if (user.getRole() == StaffRole.ADMIN) {
            client.csrf();
            client.send(post("/api/v1/auth/mfa/enroll"), "").andExpect(status().isOk());
            client.update(client.send(post("/api/v1/auth/mfa/enroll/confirm"), "{\"code\":\"" + code(userId) + "\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.session.status").value("AUTHENTICATED"))
                    .andReturn());
        }
        return client;
    }

    /** One browser: its own cookie jar, sending the CSRF header and Origin the way the frontend does. */
    private final class Client {
        private final Map<String, Cookie> jar = new HashMap<>();
        private final UUID userId;

        Client() { this((UUID) null); }
        Client(UUID userId) { this.userId = userId; }
        Client(Client copy) {
            this.userId = copy.userId;
            jar.putAll(copy.jar);
        }

        void csrf() throws Exception {
            update(perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn());
        }

        ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
            if (!jar.isEmpty()) { request.cookie(jar.values().toArray(Cookie[]::new)); }
            return mvc.perform(request);
        }

        ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
            request.header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body);
            if (jar.containsKey(AuthCookies.CSRF)) { request.header("X-CSRF-TOKEN", jar.get(AuthCookies.CSRF).getValue()); }
            return perform(request);
        }

        void update(MvcResult result) {
            for (Cookie cookie : result.getResponse().getCookies()) {
                if (cookie.getMaxAge() == 0) { jar.remove(cookie.getName()); } else { jar.put(cookie.getName(), cookie); }
            }
        }
    }
}
