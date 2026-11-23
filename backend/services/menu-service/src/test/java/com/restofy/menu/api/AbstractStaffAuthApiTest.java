package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.support.PublicMenuFixture;
import com.restofy.menu.support.StaffRequests;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import com.restofy.menu.support.TestStaffTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static com.restofy.menu.support.StaffRequests.accessCookie;
import static com.restofy.menu.support.StaffRequests.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REAL staff authentication of the management endpoints: no test actor is imported, so the production provider and
 * the production security chain decide, and the only thing that stands in for Auth is a token signed with a key pair
 * generated in the JVM (the matching public key is handed to Menu exactly the way the configuration would). Nothing
 * here touches a real Auth service, a real key or a real environment value.
 *
 * <p>Subclasses share one application context (same imports, same dynamic properties), which keeps the number of
 * PostgreSQL connection pools of the suite unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestPublicScopeConfiguration.class)
abstract class AbstractStaffAuthApiTest {

    static final UUID RESTAURANT_A = TestPublicScopeConfiguration.RESTAURANT_ALPHA;
    static final UUID RESTAURANT_B = TestPublicScopeConfiguration.RESTAURANT_BETA;
    static final String CATEGORIES = "/api/v1/menu/categories";
    static final String PRODUCTS = "/api/v1/menu/products";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    PublicMenuFixture fixture;

    @DynamicPropertySource
    static void authSettings(DynamicPropertyRegistry registry) {
        registry.add("restofy.menu.auth.staff-public-key", TestStaffTokens::authPublicKey);
        registry.add("restofy.menu.auth.issuer", () -> TestStaffTokens.ISSUER);
    }

    @BeforeEach
    void cleanBefore() {
        fixture = new PublicMenuFixture(jdbc);
        clean();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product_availability_commands");
        fixture.clean();
    }

    String adminA() {
        return TestStaffTokens.admin(RESTAURANT_A).build();
    }

    String adminB() {
        return TestStaffTokens.admin(RESTAURANT_B).build();
    }

    String token(ActorRole role, UUID restaurant) {
        return TestStaffTokens.access(UUID.randomUUID(), restaurant, role).build();
    }

    ResultActions read(String path, String token) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        return mvc.perform(token == null ? request : request.with(accessCookie(token)));
    }

    /** A complete, valid browser mutation (cookie, CSRF proof, Origin, JSON) carrying the given staff token. */
    ResultActions mutate(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(token == null ? request : request.with(accessCookie(token)));
    }

    UUID createCategory(String token, String name) throws Exception {
        MvcResult result = mutate(post(CATEGORIES), token, "{\"name\":\"" + name + "\"}").andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    UUID createProduct(String token, UUID category, String name) throws Exception {
        MvcResult result = mutate(post(PRODUCTS), token, "{\"categoryId\":\"" + category + "\",\"name\":\"" + name
                + "\",\"basePrice\":50,\"currency\":\"TRY\",\"vatRate\":10,\"priceIncludesVat\":true}")
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    static String proof() {
        return StaffRequests.proof();
    }
}
