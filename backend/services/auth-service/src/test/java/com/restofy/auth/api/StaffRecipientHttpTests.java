package com.restofy.auth.api;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import com.restofy.auth.domain.entity.StaffUser;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.infrastructure.persistence.repository.RefreshTokenRepository;
import com.restofy.auth.infrastructure.persistence.repository.StaffUserRepository;
import com.restofy.auth.infrastructure.persistence.repository.UserSessionRepository;
import com.restofy.auth.infrastructure.security.AuthCookies;
import com.restofy.auth.infrastructure.security.StaffPasswords;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class StaffRecipientHttpTests extends AuthSessionTestSupport {
    private static final KeyPair KEYS = generateKeys();
    private static final String PATH = "/api/v1/internal/auth/staff-recipients";
    private static final String RESTAURANT = "6c54daf0-9340-4a1c-981d-f9acc0261779";
    @Autowired private MockMvc mvc;
    @Autowired private StaffUserRepository users;
    @Autowired private UserSessionRepository sessions;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StaffPasswords passwords;
    private StaffUser active;

    @DynamicPropertySource
    static void notificationKey(DynamicPropertyRegistry registry) {
        registry.add("auth.services.notification-public-key", () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
    }

    @BeforeEach
    void setup() {
        refreshTokens.deleteAll();
        sessions.deleteAll();
        users.deleteAll();
        String hash = passwords.hash("Recipient-test-password-123");
        active = users.saveAndFlush(new StaffUser("recipient@example.com", hash, StaffRole.WAITER));
        StaffUser disabled = new StaffUser("disabled@example.com", hash, StaffRole.WAITER);
        disabled.deactivate();
        users.saveAndFlush(disabled);
        users.saveAndFlush(new StaffUser("kitchen@example.com", hash, StaffRole.KITCHEN));
    }

    @Test
    void returnsOnlyActiveIdsInTheRequestedRoleAndRestaurantWithoutPii() throws Exception {
        String body = mvc.perform(get(PATH).header("Authorization", token()).param("restaurantId", RESTAURANT).param("role", "WAITER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.staffUserIds.length()").value(1))
                .andExpect(jsonPath("$.staffUserIds[0]").value(active.getId().toString()))
                .andExpect(jsonPath("$.hasNext").value(false)).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("email", "password", "@", "mfa", "session");
        mvc.perform(get(PATH).header("Authorization", token()).param("restaurantId", RESTAURANT).param("role", "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.staffUserIds.length()").value(0));
    }

    @Test
    void rejectsBrowserCookiesOriginsMissingAmbiguousOrBearerCredentials() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", token()).cookie(new Cookie(AuthCookies.ACCESS, "staff-token")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", token()).header("Origin", "http://localhost:5000"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", token(), token())).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", token().replace("Service ", "Bearer ")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceCredentialCannotMutateStaffOrInvokeOtherInternalPaths() throws Exception {
        mvc.perform(post(PATH).header("Authorization", token())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/internal/auth/unknown").header("Authorization", token())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/staff").header("Authorization", token())).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongRestaurantAndInvalidParametersNeverProduceRecipientData() throws Exception {
        mvc.perform(get(PATH).header("Authorization", token()).param("restaurantId", UUID.randomUUID().toString())
                .param("role", "WAITER")).andExpect(status().isForbidden());
        mvc.perform(get(PATH).header("Authorization", token()).param("role", "WAITER")).andExpect(status().isBadRequest());
        mvc.perform(get(PATH).header("Authorization", token()).param("restaurantId", RESTAURANT).param("role", "OWNER"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).header("Authorization", token()).param("restaurantId", RESTAURANT).param("role", "WAITER")
                .param("size", "101")).andExpect(status().isBadRequest());
    }

    private static String token() {
        Instant now = Instant.now();
        return "Service " + Jwts.builder().issuer("notification-service").subject("notification-service")
                .audience().add("restofy-auth").and().claim("token_type", "service").claim("scope", "staff:recipients:read")
                .claim("restaurant_id", RESTAURANT).id(UUID.randomUUID().toString()).issuedAt(Date.from(now))
                .notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
                .signWith(KEYS.getPrivate(), Jwts.SIG.RS256).compact();
    }
}
