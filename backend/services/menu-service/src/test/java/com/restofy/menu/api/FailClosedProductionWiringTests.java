package com.restofy.menu.api;

import com.restofy.menu.application.security.CurrentActorProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No test actor imported: this is the production wiring until Auth is connected. Every category
 * endpoint must refuse, even for a caller the framework considers logged in and even when the
 * client sends identity headers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FailClosedProductionWiringTests {

    @Autowired MockMvc mvc;
    @Autowired CurrentActorProvider provider;

    @Test
    void productionProviderNeverAuthenticatesAnyone() {
        assertTrue(provider.current().isEmpty());
    }

    @Test
    void everyCategoryEndpointAnswers401() throws Exception {
        UUID id = UUID.randomUUID();
        String body = "{\"name\":\"Drinks\",\"version\":0}";
        var identity = new String[] {"X-Restaurant-Id", UUID.randomUUID().toString()};
        mvc.perform(get("/api/v1/menu/categories").with(user("staff")).header(identity[0], identity[1]).header("X-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/menu/categories/" + id).with(user("staff"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/menu/categories/" + id + "/subcategories").with(user("staff"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/menu/subcategories/" + id).with(user("staff"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/menu/categories").with(user("staff")).with(csrf()).header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/menu/categories/" + id + "/subcategories").with(user("staff")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/menu/categories/" + id).with(user("staff")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/menu/subcategories/" + id).with(user("staff")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }

    @Test
    void everyProductEndpointAnswers401() throws Exception {
        UUID id = UUID.randomUUID();
        String create = "{\"categoryId\":\"" + UUID.randomUUID() + "\",\"name\":\"Burger\",\"basePrice\":1,"
                + "\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}";
        String update = "{\"name\":\"Burger\",\"version\":0}";
        String active = "{\"isActive\":false,\"version\":0}";
        mvc.perform(get("/api/v1/menu/products/" + id).with(user("staff")).header("X-Restaurant-Id", UUID.randomUUID())
                .header("X-Role", "ADMIN")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/menu/products").with(user("staff")).header("X-Restaurant-Id", UUID.randomUUID())
                .header("X-Role", "ADMIN")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/menu/products?categoryId=" + id + "&directOnly=true&sort=name").with(user("staff")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/menu/products").with(user("staff")).with(csrf()).header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(create)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/menu/products/" + id).with(user("staff")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/menu/products/" + id + "/active").with(user("staff")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(active)).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/menu/products/" + id + "/publication").with(user("staff")).with(csrf()).header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"isPublished\":true,\"version\":0}")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/menu/products/" + id + "/availability").with(user("staff")).with(csrf())
                .header("X-Restaurant-Id", UUID.randomUUID()).header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"AVAILABLE\",\"version\":0}")).andExpect(status().isUnauthorized());
    }

    /** With the real (fail-closed) provider, even a request that would fail validation is 401 and says nothing about the rules. */
    @Test
    void invalidBodiesAndPathsAreStill401NotValidationErrorsInProduction() throws Exception {
        String id = UUID.randomUUID().toString();
        for (String path : new String[] {"/api/v1/menu/products", "/api/v1/menu/categories"}) {
            String body = mvc.perform(post(path).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
            assertTrue(!body.contains("errors") && !body.contains("must not be"), body);
            mvc.perform(post(path).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(patch("/api/v1/menu/products/not-a-uuid").with(user("staff")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":-1}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/menu/products?size=0&sort=password").with(user("staff"))).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/menu/products/" + id + "/availability").with(user("staff")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SOLD\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void requestsWithoutAnyFrameworkAuthenticationAreRefusedToo() throws Exception {
        mvc.perform(get("/api/v1/menu/categories")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/menu/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().is4xxClientError());
    }
}
