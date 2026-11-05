package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.service.CreateProductCommand;
import com.restofy.menu.application.service.ProductService;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.entity.ProductAvailability;
import com.restofy.menu.domain.entity.ProductAvailabilityHistory;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.infrastructure.persistence.repository.CategoryRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityHistoryRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A new product gets its first availability row (and history entry) in the same transaction, and starts
 * as an unpublished draft: AVAILABLE alone never makes it visible or orderable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class ProductCreationAvailabilityTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String BASE = "/api/v1/menu/products";

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;
    @Autowired ProductService service;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ProductAvailabilityRepository availabilities;
    @MockitoSpyBean ProductAvailabilityHistoryRepository histories;

    private UUID category;

    @BeforeEach
    void aCategory() {
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

    private String body() {
        return "{\"categoryId\":\"" + category + "\",\"name\":\"Burger\",\"basePrice\":0,\"currency\":\"TRY\","
                + "\"vatRate\":10,\"priceIncludesVat\":true}";
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void creatingAProductWritesAnAvailableRecordAndAFirstHistoryEntryButDoesNotMakeItOrderable() throws Exception {
        String json = mvc.perform(post(BASE).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availability.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.availability.reasonCode").doesNotExist())
                .andExpect(jsonPath("$.availability.version").value(0))
                .andExpect(jsonPath("$.isPublished").value(false))
                .andExpect(jsonPath("$.isVisibleToCustomer").value(false))
                .andExpect(jsonPath("$.canOrder").value(false))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));

        assertEquals(1, count("product_availability"));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM product_availability WHERE product_id = ?", String.class, id));
        assertEquals(RESTAURANT, jdbc.queryForObject("SELECT restaurant_id FROM product_availability WHERE product_id = ?", UUID.class, id));
        assertEquals(jdbc.queryForObject("SELECT created_by_user_id FROM products WHERE id = ?", UUID.class, id),
                jdbc.queryForObject("SELECT changed_by_user_id FROM product_availability WHERE product_id = ?", UUID.class, id));
        assertEquals(1, count("product_availability_history"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM product_availability_history "
                + "WHERE product_id = ? AND from_status IS NULL AND to_status = 'AVAILABLE'", Integer.class, id));
    }

    @Test
    void ifTheAvailabilityRowCannotBeWrittenTheProductIsNotCommitted() {
        doThrow(new IllegalStateException("availability write failed")).when(availabilities).saveAndFlush(any(ProductAvailability.class));
        var admin = new AuthenticatedActor(UUID.randomUUID(), RESTAURANT, ActorRole.ADMIN);

        var command = new CreateProductCommand(category, "Burger", null, BigDecimal.ONE, "TRY", BigDecimal.TEN, true);
        assertThrows(IllegalStateException.class, () -> service.create(admin, command));

        assertEquals(0, count("products"), "no product without an availability record");
        assertEquals(0, count("product_availability"));
        assertEquals(0, count("product_availability_history"));
    }

    @Test
    void ifTheHistoryEntryCannotBeWrittenNothingIsCommitted() {
        doThrow(new IllegalStateException("history write failed")).when(histories).save(any(ProductAvailabilityHistory.class));
        var admin = new AuthenticatedActor(UUID.randomUUID(), RESTAURANT, ActorRole.ADMIN);

        var command = new CreateProductCommand(category, "Burger", null, BigDecimal.ONE, "TRY", BigDecimal.TEN, true);
        assertThrows(IllegalStateException.class, () -> service.create(admin, command));

        assertEquals(0, count("products"));
        assertEquals(0, count("product_availability"), "the availability row is rolled back with the product");
        assertEquals(0, count("product_availability_history"));
    }

    @Test
    void aRejectedCreateWritesNoAvailabilityRecord() throws Exception {
        mvc.perform(post(BASE).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace("\"basePrice\":0", "\"basePrice\":-1")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body().replace(category.toString(), UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        assertEquals(0, count("products"));
        assertEquals(0, count("product_availability"));
        assertEquals(0, count("product_availability_history"));
    }

    @Test
    void anotherRestaurantsAdminDoesNotSeeTheAvailabilityOfMyProduct() throws Exception {
        String json = mvc.perform(post(BASE).with(user("staff")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        UUID other = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        assertTrue(availabilities.findByRestaurantIdAndProductId(other, id).isEmpty());
        assertTrue(availabilities.findByRestaurantIdAndProductId(RESTAURANT, id).isPresent());
    }
}
