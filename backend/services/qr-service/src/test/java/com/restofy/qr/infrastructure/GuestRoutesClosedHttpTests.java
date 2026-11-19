package com.restofy.qr.infrastructure;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Full security chain: guest routes carry the K3-K6 proofs but stay closed until the rate-limit change. */
@SpringBootTest(properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class GuestRoutesClosedHttpTests extends com.restofy.qr.support.Q5TestSecuritySupport {
    private static final String ORIGIN = "https://restofy.example";
    private static final String TOKEN = "A".repeat(43);
    private static final String CSRF = "B".repeat(43);
    @Autowired WebApplicationContext context;
    private MockMvc http;

    @BeforeEach
    void setup() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void completeGuestProofsAreStillDeniedWithoutReachingAController() throws Exception {
        http.perform(post("/api/v1/qr/guest-sessions/activity").header("Origin", ORIGIN).header("X-Guest-CSRF", CSRF)
                .cookie(new Cookie("restofy_guest", TOKEN), new Cookie("restofy_guest_csrf", CSRF)))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        for (String path : new String[]{"/api/v1/qr/validate", "/api/v1/qr/table-sessions/open"}) {
            http.perform(post(path).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"x\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        }
    }

    @Test
    void badOriginOrCsrfProofIsRejectedBeforeAnythingElse() throws Exception {
        http.perform(post("/api/v1/qr/guest-sessions/activity").header("Origin", "https://evil.example").header("X-Guest-CSRF", CSRF)
                .cookie(new Cookie("restofy_guest", TOKEN), new Cookie("restofy_guest_csrf", CSRF)))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/qr/guest-sessions/activity").header("Origin", ORIGIN).header("X-Guest-CSRF", "C".repeat(43))
                .cookie(new Cookie("restofy_guest", TOKEN), new Cookie("restofy_guest_csrf", CSRF)))
                .andExpect(status().isForbidden());
        http.perform(post("/api/v1/qr/validate").contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffCsrfProofIsNotAGuestProofAndTheOldBodyTokenRouteIsGone() throws Exception {
        http.perform(post("/api/v1/qr/guest-sessions/activity").header("Origin", ORIGIN).header("X-CSRF-TOKEN", CSRF)
                .cookie(new Cookie("restofy_csrf", CSRF)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"guestToken\":\"" + TOKEN + "\"}"))
                .andExpect(status().isUnauthorized());
    }
}
