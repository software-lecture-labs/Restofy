package com.restofy.menu.api;

import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestActorProvider;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two security chains: only {@code GET /api/v1/menu/public/**} is open; every other path keeps exactly what
 * Spring Boot's default gave it before the public menu existed (authentication required, CSRF enforced, health
 * open). The ADMIN endpoints' own 401 / 403 ordering is covered by the existing fail-closed and
 * authorization-before-validation suites, which run on this same configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestActorConfiguration.class, TestPublicScopeConfiguration.class})
class MenuSecurityChainApiTests {

    private static final String PUBLIC = "/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.ALPHA_REF;

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;

    @AfterEach
    void reset() {
        actor.actAsNobody();
    }

    @Test
    void thePublicMenuReadsNeedNoLogin() throws Exception {
        mvc.perform(get(PUBLIC + "/categories")).andExpect(status().isOk());
        mvc.perform(get(PUBLIC + "/products")).andExpect(status().isOk());
    }

    @Test
    void thePublicReadsCreateNoSessionAndNoCookie() throws Exception {
        var response = mvc.perform(get(PUBLIC + "/products")).andExpect(status().isOk()).andReturn().getResponse();
        assertNull(response.getHeader("Set-Cookie"));
        assertNull(response.getCookie("JSESSIONID"));
    }

    @Test
    void everyMethodOtherThanGetIsRefusedOnThePublicPath() throws Exception {
        String body = "{}";
        for (String path : new String[] {PUBLIC + "/products", PUBLIC + "/categories", "/api/v1/menu/public/anything"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
            mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
            mvc.perform(put(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
            mvc.perform(patch(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
            mvc.perform(delete(path).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post(path).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
    }

    @Test
    void theOpeningDoesNotSpreadToNeighbouringPaths() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String path : new String[] {"/api/v1/menu/publicx", "/api/v1/menu/products", "/api/v1/menu/products/" + id, "/api/v1/menu/categories",
                "/api/v1/menu/categories/" + id, "/api/v1/menu/products/" + id + "/options", "/api/v1/menu/subcategories/" + id,
                "/api/v1/menu", "/api/v1/menu/", "/api/v1/other", "/nothing"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/menu/public/../products")).andExpect(status().is4xxClientError());
    }

    @Test
    void theAdminEndpointsStillNeedAnAuthenticatedRequestAndKeepCsrf() throws Exception {
        String body = "{\"name\":\"X\"}";
        mvc.perform(post("/api/v1/menu/products").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/menu/products").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/menu/products").with(user("staff")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/menu/products/" + UUID.randomUUID()).with(user("staff")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void aLoggedInRequestWithoutAVerifiedActorIsStillRefusedOnAdminEndpoints() throws Exception {
        mvc.perform(get("/api/v1/menu/products").with(user("staff"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/menu/categories").with(user("staff")).header("X-Role", "ADMIN").header("X-Restaurant-Id", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicIdentityHeadersDoNotUnlockTheAdminSide() throws Exception {
        mvc.perform(get(PUBLIC + "/products").header("X-Role", "ADMIN")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/menu/products").header("X-Role", "ADMIN").header("X-Restaurant-Id", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthStaysOpenAndTheOtherManagementEndpointsStayClosed() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @Test
    void theUnauthorizedAnswerStillAsksForBasicCredentialsAsBefore() throws Exception {
        mvc.perform(get("/api/v1/menu/products")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));
    }
}
