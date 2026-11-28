package com.restofy.auth.api;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.AdvisoryLocks;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(StaffDirectoryHttpTests.TimeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class StaffDirectoryHttpTests extends AuthSessionTestSupport {
    private static final String ORIGIN = "http://localhost:5000";
    private static final String STAFF = "/api/v1/auth/staff";
    private static final String PASSWORD = "Staff-http-password-01";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
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
        admin = signIn(member("admin@example.com", StaffRole.ADMIN));
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
    void adminCreatesListsChangesAndDeactivatesMembersWithoutSessions() throws Exception {
        MvcResult created = admin.send(post(STAFF), "{\"email\":\"Waiter@Example.com\",\"password\":\"" + PASSWORD
                        + "\",\"role\":\"WAITER\"}")
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value("waiter@example.com")).andExpect(jsonPath("$.role").value("WAITER"))
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.pendingChange").value(false))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(content().string(not(containsString(PASSWORD)))).andReturn();
        UUID waiter = UUID.fromString(read(created, "$.id"));
        long version = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.version")).longValue();
        admin.send(post(STAFF), "{\"email\":\"WAITER@example.com\",\"password\":\"" + PASSWORD + "\",\"role\":\"KITCHEN\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("EMAIL_TAKEN"));

        admin.perform(get(STAFF + "?page=0&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].email").value("admin@example.com"))
                .andExpect(jsonPath("$.hasNext").value(true));
        admin.perform(get(STAFF + "?page=1&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].email").value("waiter@example.com")).andExpect(jsonPath("$.hasNext").value(false));

        // A member without sessions has nothing for QR to fence, so the change applies at once.
        String operation = UUID.randomUUID().toString();
        String toKitchen = "{\"role\":\"KITCHEN\",\"version\":" + version + ",\"operationId\":\"" + operation + "\"}";
        admin.send(post(STAFF + "/" + waiter + "/role"), toKitchen).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("KITCHEN")).andExpect(jsonPath("$.version").value(version + 1))
                .andExpect(jsonPath("$.pendingChange").value(false));
        admin.send(post(STAFF + "/" + waiter + "/role"), toKitchen).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("KITCHEN"));
        admin.send(post(STAFF + "/" + waiter + "/role"),
                "{\"role\":\"WAITER\",\"version\":" + version + ",\"operationId\":\"" + operation + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("OPERATION_MISMATCH"));
        admin.send(post(STAFF + "/" + waiter + "/deactivate"), deactivation(version, UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("STALE_VERSION"));

        admin.send(post(STAFF + "/" + waiter + "/deactivate"), deactivation(version + 1, UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.role").value("KITCHEN"));
        admin.send(post(STAFF + "/" + waiter + "/deactivate"), deactivation(version + 2, UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_ACTIVE"));
        Client former = new Client();
        former.csrf();
        former.send(post("/api/v1/auth/login"), "{\"email\":\"waiter@example.com\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isUnauthorized());
        assertThat(users.findById(waiter).orElseThrow()).satisfies(user -> {
            assertThat(user.getRole()).isEqualTo(StaffRole.KITCHEN);
            assertThat(user.isActive()).isFalse();
        });
    }

    @Test
    void signedInMemberIsFencedUntilQrAcknowledgesTheChange() throws Exception {
        UUID waiterId = member("waiter@example.com", StaffRole.WAITER);
        Client waiter = signIn(waiterId);
        String operation = UUID.randomUUID().toString();
        String toKitchen = "{\"role\":\"KITCHEN\",\"version\":0,\"operationId\":\"" + operation + "\"}";

        admin.send(post(STAFF + "/" + waiterId + "/role"), toKitchen).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.role").value("WAITER")).andExpect(jsonPath("$.pendingChange").value(true));
        waiter.perform(get("/api/v1/auth/session")).andExpect(status().isServiceUnavailable());
        admin.send(post(STAFF + "/" + waiterId + "/deactivate"), deactivation(0, UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("TRANSITION_PENDING"));
        admin.perform(get(STAFF)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.email=='waiter@example.com')].pendingChange").value(true));
        admin.send(post(STAFF + "/" + waiterId + "/role"), toKitchen).andExpect(status().isAccepted());

        acknowledgePreparedRevocationsForAuthFixture();
        admin.send(post(STAFF + "/" + waiterId + "/role"), toKitchen).andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("KITCHEN")).andExpect(jsonPath("$.pendingChange").value(false));
        waiter.perform(get("/api/v1/auth/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void aMemberOnTheirWayOutCannotRemoveTheRemainingAdministrator() throws Exception {
        UUID secondId = member("second-admin@example.com", StaffRole.ADMIN);
        Client second = signIn(secondId);
        admin.send(post(STAFF + "/" + secondId + "/deactivate"), deactivation(0, UUID.randomUUID()))
                .andExpect(status().isAccepted());
        second.send(post(STAFF + "/" + admin.userId + "/deactivate"), deactivation(0, UUID.randomUUID()))
                .andExpect(status().isServiceUnavailable());
        second.send(post(STAFF + "/" + admin.userId + "/role"),
                "{\"role\":\"WAITER\",\"version\":0,\"operationId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isServiceUnavailable());
        assertThat(users.findById(admin.userId).orElseThrow()).satisfies(user -> {
            assertThat(user.getRole()).isEqualTo(StaffRole.ADMIN);
            assertThat(user.isActive()).isTrue();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations WHERE user_id = ?",
                Long.class, admin.userId)).isZero();
    }

    @Test
    void refusesSelfChangesStaleOrPointlessChangesUnknownMembersAndMalformedInput() throws Exception {
        UUID waiter = member("waiter@example.com", StaffRole.WAITER);
        admin.send(post(STAFF + "/" + admin.userId + "/deactivate"), deactivation(0, UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SELF_CHANGE"));
        admin.send(post(STAFF + "/" + admin.userId + "/role"),
                "{\"role\":\"WAITER\",\"version\":0,\"operationId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("SELF_CHANGE"));
        admin.send(post(STAFF + "/" + waiter + "/role"),
                "{\"role\":\"WAITER\",\"version\":0,\"operationId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NO_CHANGE"));
        admin.send(post(STAFF + "/" + UUID.randomUUID() + "/deactivate"), deactivation(0, UUID.randomUUID()))
                .andExpect(status().isNotFound());
        admin.send(post(STAFF + "/not-a-uuid/deactivate"), deactivation(0, UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        for (String body : new String[]{"{\"version\":0}", "{\"operationId\":\"" + UUID.randomUUID() + "\"}",
                "{\"version\":-1,\"operationId\":\"" + UUID.randomUUID() + "\"}", "{\"version\":0,\"operationId\":\"x\"}"}) {
            admin.send(post(STAFF + "/" + waiter + "/deactivate"), body).andExpect(status().isBadRequest());
        }
        admin.send(post(STAFF + "/" + waiter + "/role"),
                "{\"role\":\"OWNER\",\"version\":0,\"operationId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest());
        for (String body : new String[]{
                "{\"email\":\"not-an-address\",\"password\":\"" + PASSWORD + "\",\"role\":\"WAITER\"}",
                "{\"email\":\"short@example.com\",\"password\":\"too-short\",\"role\":\"WAITER\"}",
                "{\"email\":\"norole@example.com\",\"password\":\"" + PASSWORD + "\"}",
                "{\"email\":\"long@example.com\",\"password\":\"" + "x".repeat(73) + "\",\"role\":\"WAITER\"}"}) {
            admin.send(post(STAFF), body).andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString(PASSWORD))));
        }
        for (String query : new String[]{"?size=101", "?size=0", "?page=-1", "?page=10001", "?size=abc"}) {
            admin.perform(get(STAFF + query)).andExpect(status().isBadRequest());
        }
        assertThat(users.count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations", Long.class)).isZero();
    }

    @Test
    void aConcurrentStaffChangeIsAskedToRetryInsteadOfQueued() throws Exception {
        UUID waiter = member("waiter@example.com", StaffRole.WAITER);
        UUID operation = UUID.randomUUID();
        try (Connection holder = dataSource.getConnection(); Statement statement = holder.createStatement()) {
            holder.setAutoCommit(false);
            statement.execute("SELECT pg_advisory_xact_lock(" + AdvisoryLocks.STAFF_ADMINISTRATION + ")");
            admin.send(post(STAFF + "/" + waiter + "/deactivate"), deactivation(0, operation))
                    .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.reason").value("BUSY"));
            holder.rollback();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations", Long.class)).isZero();
        admin.send(post(STAFF + "/" + waiter + "/deactivate"), deactivation(0, operation))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void endpointsRejectAnonymousCallersOtherRolesUnfinishedMfaMissingCsrfAndForeignOrigins() throws Exception {
        UUID target = member("target@example.com", StaffRole.WAITER);
        String create = "{\"email\":\"new@example.com\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"}";
        String deactivate = deactivation(0, UUID.randomUUID());
        String role = "{\"role\":\"ADMIN\",\"version\":0,\"operationId\":\"" + UUID.randomUUID() + "\"}";

        Client anonymous = new Client();
        anonymous.csrf();
        anonymous.perform(get(STAFF)).andExpect(status().isUnauthorized());
        anonymous.send(post(STAFF), create).andExpect(status().isUnauthorized());
        anonymous.send(post(STAFF + "/" + target + "/role"), role).andExpect(status().isUnauthorized());

        for (StaffRole staffRole : new StaffRole[]{StaffRole.WAITER, StaffRole.KITCHEN}) {
            Client member = signIn(member(staffRole.name().toLowerCase(Locale.ROOT) + "@example.com", staffRole));
            member.perform(get(STAFF).header("X-Role", "ADMIN")).andExpect(status().isForbidden());
            member.send(post(STAFF).header("X-Role", "ADMIN"), create).andExpect(status().isForbidden());
            member.send(post(STAFF + "/" + target + "/role"), role).andExpect(status().isForbidden());
            member.send(post(STAFF + "/" + target + "/deactivate"), deactivate).andExpect(status().isForbidden());
        }

        Client unfinished = new Client();
        unfinished.csrf();
        unfinished.update(unfinished.send(post("/api/v1/auth/login"),
                "{\"email\":\"admin@example.com\",\"password\":\"" + PASSWORD + "\"}").andExpect(status().isOk()).andReturn());
        unfinished.perform(get(STAFF)).andExpect(status().isForbidden());
        unfinished.send(post(STAFF), create).andExpect(status().isForbidden());

        admin.perform(post(STAFF).contentType(MediaType.APPLICATION_JSON).header("Origin", ORIGIN).content(create))
                .andExpect(status().isForbidden());
        admin.perform(post(STAFF).contentType(MediaType.APPLICATION_JSON).header("Origin", "https://evil.example")
                .header("X-CSRF-TOKEN", admin.jar.get(AuthCookies.CSRF).getValue()).content(create))
                .andExpect(status().isForbidden());
        admin.perform(get(STAFF).header("Origin", "https://evil.example")).andExpect(status().isForbidden());
        admin.perform(post(STAFF + "/" + target).contentType(MediaType.APPLICATION_JSON).header("Origin", ORIGIN)
                .header("X-CSRF-TOKEN", admin.jar.get(AuthCookies.CSRF).getValue()).content("{}"))
                .andExpect(status().isUnauthorized()); // Unlisted path: no session is read, and everything else is denied.
        assertThat(users.findByEmailNormalized("new@example.com")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations", Long.class)).isZero();
    }

    @Test
    void changesAreRateLimitedPerAdministrator() throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            admin.send(post(STAFF + "/" + UUID.randomUUID() + "/deactivate"), deactivation(0, UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }
        admin.send(post(STAFF + "/" + UUID.randomUUID() + "/deactivate"), deactivation(0, UUID.randomUUID()))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        admin.send(post(STAFF), "{\"email\":\"late@example.com\",\"password\":\"" + PASSWORD + "\",\"role\":\"WAITER\"}")
                .andExpect(status().isTooManyRequests());
        admin.perform(get(STAFF)).andExpect(status().isOk());
        assertThat(users.findByEmailNormalized("late@example.com")).isEmpty();
    }

    @Test
    void grantingAdministratorNeedsTheCallersPasswordAndASecondFactor() throws Exception {
        String newAdmin = "{\"email\":\"second-admin@example.com\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"";
        admin.send(post(STAFF), newAdmin + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("STEP_UP_REQUIRED"));
        admin.send(post(STAFF), newAdmin + confirmation("Wrong-password-0001", nextCode(admin)))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.reason").value("STEP_UP_REJECTED"))
                .andExpect(content().string(not(containsString(PASSWORD))));
        admin.perform(get(STAFF)).andExpect(status().isOk()); // A wrong step-up does not end the session.
        admin.send(post(STAFF), newAdmin + ",\"confirmation\":{\"password\":\"" + PASSWORD
                        + "\",\"totpCode\":\"123456\",\"recoveryCode\":\"aaaa-bbbb-cccc-dddd-eeee\"}}")
                .andExpect(status().isBadRequest());
        assertThat(users.findByEmailNormalized("second-admin@example.com")).isEmpty();

        String confirmed = newAdmin + confirmation(PASSWORD, nextCode(admin));
        admin.send(post(STAFF), confirmed).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("ADMIN"));
        // The code is spent: replaying the same request body cannot mint another administrator.
        admin.send(post(STAFF), confirmed.replace("second-admin@", "third-admin@"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.reason").value("STEP_UP_REJECTED"));
        assertThat(users.findByEmailNormalized("third-admin@example.com")).isEmpty();

        // The new ADMIN chose no factor yet: the first sign-in must enrol MFA before anything else is allowed.
        Client fresh = new Client();
        fresh.csrf();
        fresh.update(fresh.send(post("/api/v1/auth/login"),
                "{\"email\":\"second-admin@example.com\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk()).andReturn());
        fresh.perform(get("/api/v1/auth/session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MFA_PENDING")).andExpect(jsonPath("$.mfaSetupRequired").value(true));
        fresh.perform(get(STAFF)).andExpect(status().isForbidden());

        UUID waiter = member("waiter@example.com", StaffRole.WAITER);
        String promote = "{\"role\":\"ADMIN\",\"version\":0,\"operationId\":\"" + UUID.randomUUID() + "\"";
        admin.send(post(STAFF + "/" + waiter + "/role"), promote + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("STEP_UP_REQUIRED"));
        admin.send(post(STAFF + "/" + waiter + "/role"), promote + confirmation(PASSWORD, nextCode(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        // Repeating the recorded grant creates nothing new, so it needs no second step-up.
        admin.send(post(STAFF + "/" + waiter + "/role"), promote + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
        // Taking ADMIN away grants nothing and needs no step-up.
        admin.send(post(STAFF + "/" + waiter + "/role"),
                "{\"role\":\"WAITER\",\"version\":1,\"operationId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("WAITER"));
    }

    @Test
    void aRecoveryCodeConfirmsAnAdministratorGrantOnlyOnce() throws Exception {
        String recoveryCode = admin.recoveryCodes.getFirst();
        String body = "{\"email\":\"second-admin@example.com\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\""
                + ",\"confirmation\":{\"password\":\"" + PASSWORD + "\",\"recoveryCode\":\"" + recoveryCode + "\"}}";
        admin.send(post(STAFF), body).andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("ADMIN"));
        // The recovery code is consumed like at sign-in: it cannot confirm a second grant.
        admin.send(post(STAFF), body.replace("second-admin@", "third-admin@"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.reason").value("STEP_UP_REJECTED"));
        assertThat(users.findByEmailNormalized("third-admin@example.com")).isEmpty();
        // Another unused code still works, so only the spent one was taken.
        String other = body.replace("second-admin@", "fourth-admin@").replace(recoveryCode, admin.recoveryCodes.get(1));
        admin.send(post(STAFF), other).andExpect(status().isCreated());
    }

    @Test
    void wrongStepUpsCountTowardsTheMfaLock() throws Exception {
        String body = "{\"email\":\"second-admin@example.com\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\""
                + confirmation("Wrong-password-0001", "000000");
        for (int attempt = 1; attempt < 5; attempt++) {
            admin.send(post(STAFF), body).andExpect(status().isUnauthorized());
        }
        admin.send(post(STAFF), body).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        String correct = "{\"email\":\"second-admin@example.com\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\""
                + confirmation(PASSWORD, nextCode(admin));
        admin.send(post(STAFF), correct).andExpect(status().isTooManyRequests());
        assertThat(users.findByEmailNormalized("second-admin@example.com")).isEmpty();
    }

    @Test
    void anOperationIdBelongsToOneChangeAndAnyAdministratorMayRepeatIt(CapturedOutput output) throws Exception {
        UUID leaving = member("leaving@example.com", StaffRole.WAITER);
        UUID other = member("other@example.com", StaffRole.WAITER);
        signIn(leaving);
        Client second = signIn(member("second-admin@example.com", StaffRole.ADMIN));
        UUID operation = UUID.randomUUID();

        admin.send(post(STAFF + "/" + leaving + "/deactivate"), deactivation(0, operation))
                .andExpect(status().isAccepted());
        admin.send(post(STAFF + "/" + other + "/deactivate"), deactivation(0, operation))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("OPERATION_MISMATCH"));
        admin.send(post(STAFF + "/" + leaving + "/role"),
                "{\"role\":\"KITCHEN\",\"version\":0,\"operationId\":\"" + operation + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("OPERATION_MISMATCH"));
        second.send(post(STAFF + "/" + leaving + "/deactivate"), deactivation(0, operation))
                .andExpect(status().isAccepted());

        acknowledgePreparedRevocationsForAuthFixture();
        second.send(post(STAFF + "/" + leaving + "/deactivate"), deactivation(0, operation))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertThat(users.findById(other).orElseThrow().isActive()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations", Long.class)).isOne();
        assertThat(output)
                .contains("staff_event=DEACTIVATION_REQUESTED actor_id=" + admin.userId + " target_id=" + leaving
                        + " role=null operation_id=" + operation + " retry=false")
                .contains("staff_event=DEACTIVATION_REQUESTED actor_id=" + second.userId + " target_id=" + leaving
                        + " role=null operation_id=" + operation + " retry=true")
                .doesNotContain("leaving@example.com").doesNotContain(PASSWORD);
    }

    @Test
    void nothingIsRecordedWhileTheQrSourceIsNotActive() throws Exception {
        UUID waiter = member("waiter@example.com", StaffRole.WAITER);
        jdbc.update("UPDATE staff_revocation_source_state SET phase='CLOSED' WHERE singleton=true");
        try {
            admin.send(post(STAFF + "/" + waiter + "/deactivate"), deactivation(0, UUID.randomUUID()))
                    .andExpect(status().isServiceUnavailable());
            admin.send(post(STAFF + "/" + waiter + "/role"),
                    "{\"role\":\"KITCHEN\",\"version\":0,\"operationId\":\"" + UUID.randomUUID() + "\"}")
                    .andExpect(status().isServiceUnavailable());
        } finally {
            jdbc.update("UPDATE staff_revocation_source_state SET phase='ACTIVE' WHERE singleton=true");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_revocation_operations", Long.class)).isZero();
        assertThat(users.findById(waiter).orElseThrow()).satisfies(user -> {
            assertThat(user.getRole()).isEqualTo(StaffRole.WAITER);
            assertThat(user.isActive()).isTrue();
        });
    }

    /** A fresh TOTP code for the client's ADMIN: moves the clock one step on, since a code is accepted only once. */
    private String nextCode(Client client) {
        clock.advance(30);
        byte[] secret = cipher.decrypt(client.userId,
                records.findByStaffUserId(client.userId).orElseThrow().getEncryptedTotpSecret());
        return totp.code(secret, clock.instant().getEpochSecond() / 30, 6);
    }

    /** Closes a JSON object that is still open, adding the caller's step-up. */
    private static String confirmation(String password, String totpCode) {
        return ",\"confirmation\":{\"password\":\"" + password + "\",\"totpCode\":\"" + totpCode + "\"}}";
    }

    private UUID member(String email, StaffRole role) {
        return users.saveAndFlush(new StaffUser(email, passwords.hash(PASSWORD), role)).getId();
    }

    private Client signIn(UUID userId) throws Exception {
        StaffUser user = users.findById(userId).orElseThrow();
        Client client = new Client(userId);
        client.csrf();
        client.update(client.send(post("/api/v1/auth/login"),
                "{\"email\":\"" + user.getEmailNormalized() + "\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk()).andReturn());
        if (user.getRole() == StaffRole.ADMIN) {
            client.csrf();
            client.send(post("/api/v1/auth/mfa/enroll"), "").andExpect(status().isOk());
            byte[] secret = cipher.decrypt(userId, records.findByStaffUserId(userId).orElseThrow().getEncryptedTotpSecret());
            String code = totp.code(secret, clock.instant().getEpochSecond() / 30, 6);
            MvcResult enrolled = client.send(post("/api/v1/auth/mfa/enroll/confirm"), "{\"code\":\"" + code + "\"}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.session.status").value("AUTHENTICATED")).andReturn();
            client.update(enrolled);
            client.recoveryCodes = JsonPath.read(enrolled.getResponse().getContentAsString(), "$.recoveryCodes");
        }
        return client;
    }

    private static String deactivation(long version, UUID operation) {
        return "{\"version\":" + version + ",\"operationId\":\"" + operation + "\"}";
    }

    private static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }

    /** One browser: its own cookie jar, sending the CSRF header and Origin the way the frontend does. */
    private final class Client {
        private final Map<String, Cookie> jar = new HashMap<>();
        private final UUID userId;
        private List<String> recoveryCodes = List.of();

        Client() { this(null); }
        Client(UUID userId) { this.userId = userId; }

        void csrf() throws Exception {
            var request = get("/api/v1/auth/csrf");
            if (!jar.isEmpty()) { request.cookie(cookies()); }
            update(mvc.perform(request).andExpect(status().isOk()).andReturn());
        }

        ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
            if (!jar.isEmpty()) { request.cookie(cookies()); }
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

        private Cookie[] cookies() { return jar.values().toArray(Cookie[]::new); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableAuthClock clock() { return new MutableAuthClock(); }
    }
}
