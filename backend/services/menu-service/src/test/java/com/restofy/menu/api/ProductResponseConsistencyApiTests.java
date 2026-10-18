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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every endpoint that returns a product reports the same name, description, price, currency and VAT. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductResponseConsistencyApiTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String BASE = "/api/v1/menu/products";
    private static final List<String> CORE = List.of("id", "categoryId", "name", "description", "ingredientsText", "basePrice", "currency",
            "vatRate", "priceIncludesVat");

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;

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

    private String send(MockHttpServletRequestBuilder request, String json, int expected) throws Exception {
        return mvc.perform(request.with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    private String getOne(UUID id) throws Exception {
        return mvc.perform(get(BASE + "/" + id).with(user("staff"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> core(String json) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : CORE) {
            Object value = JsonPath.read(json, "$." + field);
            result.put(field, value instanceof Number number ? new BigDecimal(number.toString()).stripTrailingZeros() : value);
        }
        return result;
    }

    @Test
    void createGetListAndEveryCommandAgreeOnTheCoreFields() throws Exception {
        String created = send(post(BASE), "{\"categoryId\":\"" + category + "\",\"name\":\"  Lamb   kebab \","
                + "\"description\":\" grilled \",\"ingredientsText\":\" lamb, spices \",\"basePrice\":125.5,\"currency\":\"TRY\",\"vatRate\":8.5,"
                + "\"priceIncludesVat\":true}", 201);
        UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
        Map<String, Object> expected = core(created);
        assertEquals("Lamb kebab", expected.get("name"));
        assertEquals("grilled", expected.get("description"));
        assertEquals("lamb, spices", expected.get("ingredientsText"));
        assertEquals(new BigDecimal("125.5"), expected.get("basePrice"));
        assertEquals(new BigDecimal("8.5"), expected.get("vatRate"));

        assertEquals(expected, core(getOne(id)));
        String list = mvc.perform(get(BASE).with(user("staff"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(expected, core(toJson(list)));

        assertEquals(expected, core(send(patch(BASE + "/" + id + "/publication"), "{\"isPublished\":true,\"version\":0}", 200)));
        assertEquals(expected, core(send(patch(BASE + "/" + id + "/active"), "{\"isActive\":false,\"version\":1}", 200)));
        assertEquals(expected, core(send(patch(BASE + "/" + id + "/availability"),
                "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":0}", 200)));
        assertEquals(expected, core(getOne(id)));
    }

    /** First item of a list response as a standalone JSON document. */
    private String toJson(String list) throws Exception {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(JsonPath.read(list, "$.items[0]"));
    }

    @Test
    void anUpdateChangesOnlyTheSentFieldsAndEveryReadShowsTheNewValues() throws Exception {
        String created = send(post(BASE), "{\"categoryId\":\"" + category + "\",\"name\":\"Tea\",\"description\":\"hot\","
                + "\"basePrice\":5,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}", 201);
        UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
        String updated = send(patch(BASE + "/" + id), "{\"basePrice\":0,\"vatRate\":0,\"priceIncludesVat\":false,\"version\":0}", 200);

        Map<String, Object> after = core(updated);
        assertEquals(0, ((BigDecimal) after.get("basePrice")).signum(), "a zero price is stored and returned as zero");
        assertEquals(0, ((BigDecimal) after.get("vatRate")).signum());
        assertEquals("Tea", after.get("name"));
        assertEquals("hot", after.get("description"));
        assertEquals("TRY", after.get("currency"));
        assertEquals(false, after.get("priceIncludesVat"));
        assertEquals(after, core(getOne(id)));
    }
}
