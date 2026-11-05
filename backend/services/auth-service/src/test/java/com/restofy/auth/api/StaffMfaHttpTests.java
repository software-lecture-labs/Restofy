package com.restofy.auth.api;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.StaffMfaRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.security.*;
import com.restofy.auth.support.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(StaffMfaHttpTests.TimeConfiguration.class)
class StaffMfaHttpTests extends AuthSessionTestSupport {
    private static final String ORIGIN = "http://localhost:5000";
    private static final String PASSWORD = "Mfa-http-password-01";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StaffUserRepository users;
    @Autowired private StaffMfaRepository records;
    @Autowired private StaffPasswords passwords;
    @Autowired private MfaSecretCipher cipher;
    @Autowired private TotpVerifier totp;
    @Autowired private MutableAuthClock clock;
    private final Map<String, Cookie> jar = new HashMap<>();
    private UUID adminId;

    @BeforeEach
    void setup() {
        clean();
        clock.reset();
        jar.clear();
        adminId = users.saveAndFlush(new StaffUser("admin@example.com", passwords.hash(PASSWORD), StaffRole.ADMIN)).getId();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM mfa_recovery_codes");
        jdbc.update("DELETE FROM staff_mfa");
        jdbc.update("DELETE FROM refresh_tokens");
        jdbc.update("DELETE FROM user_sessions");
        jdbc.update("DELETE FROM staff_users");
    }

    @Test
    void enrollmentRotatesCookiesAndCsrfAndReturnsCodesOnlyOnce() throws Exception {
        login("admin@example.com");
        mvc.perform(get("/api/v1/auth/session").cookie(cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaSetupRequired").value(true));
        mvc.perform(mutation("/mfa/enroll")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.secret").isString()).andExpect(jsonPath("$.otpauthUri").isString());
        Map<String, Cookie> old = new HashMap<>(jar);
        String code = currentCode();
        MvcResult completed = mvc.perform(mutation("/mfa/enroll/confirm").content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.session.status").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.recoveryCodes.length()").value(10))
                .andExpect(jsonPath("$.access").doesNotExist()).andReturn();
        update(completed);
        assertThat(jar).containsKeys(AuthCookies.ACCESS, AuthCookies.REFRESH).doesNotContainKey(AuthCookies.MFA);
        mvc.perform(post("/api/v1/auth/activity").header("Origin", ORIGIN).cookie(cookies())
                .header("X-CSRF-TOKEN", old.get(AuthCookies.CSRF).getValue())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/session").cookie(old.values().toArray(Cookie[]::new))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHENTICATED")).andExpect(jsonPath("$.secret").doesNotExist())
                .andExpect(jsonPath("$.recoveryCodes").doesNotExist());
        csrf();
        mvc.perform(mutation("/activity")).andExpect(status().isNoContent());
        mvc.perform(mutation("/mfa/enroll")).andExpect(status().isForbidden());
        mvc.perform(mutation("/logout")).andExpect(status().isNoContent());
        jar.clear();
        login("admin@example.com");
        mvc.perform(get("/api/v1/auth/session").cookie(cookies())).andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaSetupRequired").value(false));
        clock.advance(30);
        update(mvc.perform(mutation("/mfa/verify").content("{\"code\":\"" + currentCode() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.recoveryCodes").doesNotExist()).andReturn());
    }

    @Test
    void endpointsRejectAnonymousWrongRolesCsrfAndForgedHeaders() throws Exception {
        for (String path : new String[]{"/mfa/enroll", "/mfa/enroll/confirm", "/mfa/verify", "/mfa/recover", "/mfa/recovery-codes/regenerate"}) {
            csrf();
            mvc.perform(mutation(path).header("X-Role", "ADMIN").header("X-User-Id", adminId.toString()).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        for (StaffRole role : new StaffRole[]{StaffRole.WAITER, StaffRole.KITCHEN}) {
            String email = role.name().toLowerCase(java.util.Locale.ROOT) + "@example.com";
            users.saveAndFlush(new StaffUser(email, passwords.hash(PASSWORD), role));
            jar.clear();
            login(email);
            for (String path : new String[]{"/mfa/enroll", "/mfa/enroll/confirm", "/mfa/verify", "/mfa/recover", "/mfa/recovery-codes/regenerate"}) {
                mvc.perform(mutation(path).header("X-Role", "ADMIN")
                        .content("{\"password\":\"" + PASSWORD + "\",\"totpCode\":\"123456\",\"code\":\"123456\"}"))
                        .andExpect(status().isForbidden());
            }
        }
        jar.clear();
        login("admin@example.com");
        for (String path : new String[]{"/mfa/enroll", "/mfa/enroll/confirm", "/mfa/verify", "/mfa/recover", "/mfa/recovery-codes/regenerate"}) {
            mvc.perform(post("/api/v1/auth" + path).cookie(cookies()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/auth/mfa/status").cookie(cookies())).andExpect(status().isUnauthorized());
        mvc.perform(mutation("/activity")).andExpect(status().isForbidden());
    }

    @Test
    void malformedCodesAndSharedRateLimitHaveGenericNonSensitiveResponses() throws Exception {
        login("admin@example.com");
        mvc.perform(mutation("/mfa/enroll")).andExpect(status().isOk());
        mvc.perform(mutation("/mfa/enroll/confirm").content("{\"code\":\"not-an-otp\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("not-an-otp"))));
        String wrong = "000000";
        if (totp.verify(secret(), wrong, null) >= 0) { wrong = "000001"; }
        for (int index = 0; index < 4; index++) {
            mvc.perform(mutation("/mfa/enroll/confirm").content("{\"code\":\"" + wrong + "\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mvc.perform(mutation("/mfa/recover").content("{\"code\":\"AAAA-AAAA-AAAA-AAAA-AAAA\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "300"));
        mvc.perform(mutation("/mfa/enroll/confirm").content("{\"code\":\"" + currentCode() + "\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    private void login(String email) throws Exception {
        csrf();
        update(mvc.perform(mutation("/login").content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn());
        csrf();
    }
    private void csrf() throws Exception {
        var request = get("/api/v1/auth/csrf");
        if (!jar.isEmpty()) { request.cookie(cookies()); }
        update(mvc.perform(request).andExpect(status().isOk()).andReturn());
    }
    private Cookie[] cookies() { return jar.values().toArray(Cookie[]::new); }
    private MockHttpServletRequestBuilder mutation(String path) {
        var request = post("/api/v1/auth" + path).header("Origin", ORIGIN).cookie(cookies()).contentType(MediaType.APPLICATION_JSON);
        if (jar.containsKey(AuthCookies.CSRF)) { request.header("X-CSRF-TOKEN", jar.get(AuthCookies.CSRF).getValue()); }
        return request;
    }
    private void update(MvcResult result) {
        for (Cookie cookie : result.getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0) { jar.remove(cookie.getName()); } else { jar.put(cookie.getName(), cookie); }
        }
    }
    private byte[] secret() { return cipher.decrypt(adminId, records.findByStaffUserId(adminId).orElseThrow().getEncryptedTotpSecret()); }
    private String currentCode() { return totp.code(secret(), clock.instant().getEpochSecond() / 30, 6); }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableAuthClock clock() { return new MutableAuthClock(); }
    }
}
