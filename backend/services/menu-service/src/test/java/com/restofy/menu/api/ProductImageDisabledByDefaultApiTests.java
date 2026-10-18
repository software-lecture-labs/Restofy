package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestActorProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The shipped default: no image origin is configured, so no image URL can be added, only removed. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductImageDisabledByDefaultApiTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String BASE = "/api/v1/menu/products";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;

    private UUID category;

    @BeforeEach
    void data() {
        category = categories.saveAndFlush(Category.root(RESTAURANT, "Main", 0)).getId();
        actor.actAs(RESTAURANT, ActorRole.ADMIN);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product_availability_history");
        jdbc.update("DELETE FROM product_availability");
        jdbc.update("DELETE FROM products");
        jdbc.update("DELETE FROM categories");
        actor.actAsNobody();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID created() throws Exception {
        String json = send(post(BASE), "{\"categoryId\":\"" + category + "\",\"name\":\"Tea\",\"basePrice\":5,\"currency\":\"TRY\","
                + "\"vatRate\":10,\"priceIncludesVat\":true}").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    @Test
    void theShippedConfigurationHasNoAllowedOrigin() {
        String configured = environment.getProperty("restofy.menu.image.allowed-origins");
        assertTrue(configured == null || configured.isBlank(), "the property ships empty; only an operator sets MENU_IMAGE_ALLOWED_ORIGINS");
    }

    @Test
    void noImageUrlCanBeAddedAndTheMessageSaysWhy() throws Exception {
        UUID id = created();
        for (String url : new String[] {"https://images.example/a.png", "https://cdn.example/a.webp", "https://any-host.example/a.jpg"}) {
            String body = send(patch(BASE + "/" + id), "{\"imageUrl\":\"" + url + "\",\"version\":0}").andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertEquals("Product images are not enabled: no image origin is configured.", JsonPath.read(body, "$.detail"));
        }
        assertNull(jdbc.queryForObject("SELECT image_url FROM products WHERE id = ?", String.class, id));
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM products WHERE id = ?", Long.class, id));
    }

    @Test
    void removingStillWorksSoAnImageStoredBeforeTheOriginWasRemovedCanBeCleared() throws Exception {
        UUID id = created();
        jdbc.update("UPDATE products SET image_url = 'https://old.example/a.png' WHERE id = ?", id);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(BASE + "/" + id).with(user("staff")))
                .andExpect(jsonPath("$.imageUrl").value("https://old.example/a.png"));
        send(patch(BASE + "/" + id), "{\"imageUrl\":\"\",\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").doesNotExist());
        assertNull(jdbc.queryForObject("SELECT image_url FROM products WHERE id = ?", String.class, id));
    }

    @Test
    void everyOtherProductEditKeepsWorkingAndAStoredImageIsNotRevalidated() throws Exception {
        UUID id = created();
        jdbc.update("UPDATE products SET image_url = 'https://old.example/a.png' WHERE id = ?", id);
        send(patch(BASE + "/" + id), "{\"name\":\"Renamed\",\"basePrice\":0,\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("https://old.example/a.png"));
    }
}
