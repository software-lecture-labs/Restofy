package com.restofy.menu.api;

import com.jayway.jsonpath.JsonPath;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.support.TestActorConfiguration;
import com.restofy.menu.support.TestActorProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Authentication (401) and authorization (403) are decided BEFORE the body is parsed or validated and before
 * a path value is converted, so a caller without rights never learns the validation rules and always gets
 * 401/403, never 400. Covers every management endpoint of products and categories.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestActorConfiguration.class)
class AuthorizationBeforeValidationApiTests {

    private static final UUID RESTAURANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String P = "/api/v1/menu/products";
    private static final String C = "/api/v1/menu/categories";
    private static final String S = "/api/v1/menu/subcategories";

    /** Every endpoint, each with an input that would fail validation or conversion for an ADMIN (400). */
    private record Call(HttpMethod method, String path, String body) {
        @Override public String toString() { return method + " " + path; }
    }

    private static List<Call> invalidCalls() {
        String id = UUID.randomUUID().toString();
        return List.of(
                new Call(HttpMethod.POST, P, "{}"),
                new Call(HttpMethod.POST, P, "{not json"),
                new Call(HttpMethod.POST, P, "{\"name\":\"a\\u0000\",\"basePrice\":-1,\"currency\":\"ZZZ\",\"vatRate\":500}"),
                new Call(HttpMethod.PATCH, P + "/" + id, "{}"),
                new Call(HttpMethod.PATCH, P + "/not-a-uuid", "{\"version\":-1}"),
                new Call(HttpMethod.PATCH, P + "/" + id + "/active", "{}"),
                new Call(HttpMethod.PATCH, P + "/" + id + "/publication", "{}"),
                new Call(HttpMethod.PATCH, P + "/" + id + "/availability", "{\"status\":\"SOLD\"}"),
                new Call(HttpMethod.GET, P + "/not-a-uuid", null),
                new Call(HttpMethod.GET, P + "?size=0&page=-1&sort=password&categoryId=not-a-uuid&directOnly=maybe", null),
                new Call(HttpMethod.POST, C, "{}"),
                new Call(HttpMethod.POST, C, "{\"name\":\"a\\u0000\"}"),
                new Call(HttpMethod.POST, C + "/" + id + "/subcategories", "{}"),
                new Call(HttpMethod.PATCH, C + "/" + id, "{}"),
                new Call(HttpMethod.PATCH, S + "/" + id, "{\"version\":-1}"),
                new Call(HttpMethod.GET, C + "?size=0&page=-1&sort=password", null),
                new Call(HttpMethod.GET, C + "/not-a-uuid", null),
                new Call(HttpMethod.GET, C + "/" + id + "/subcategories?size=500", null),
                new Call(HttpMethod.GET, S + "/not-a-uuid", null));
    }

    @Autowired MockMvc mvc;
    @Autowired TestActorProvider actor;

    @AfterEach
    void reset() {
        actor.actAsNobody();
    }

    private MvcResult run(Call call) throws Exception {
        var builder = request(call.method(), call.path()).with(user("staff")).with(csrf());
        if (call.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(call.body());
        }
        return mvc.perform(builder).andReturn();
    }

    private void assertOnlyAFixedProblem(MvcResult result, int status) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertEquals(status, result.getResponse().getStatus(), content);
        assertFalse(content.contains("\"errors\""), "no validation details: " + content);
        assertTrue(result.getResponse().getContentType().startsWith("application/problem+json"));
        String detail = JsonPath.read(content, "$.detail");
        assertTrue(detail.equals("Authentication is required.") || detail.equals("The caller is not allowed to perform this operation."), detail);
        for (String leak : List.of("name", "basePrice", "currency", "vatRate", "version", "status", "page", "size", "sort", "uuid")) {
            assertFalse(detail.toLowerCase().contains(leak), "detail must not mention " + leak);
        }
    }

    @Test
    void withoutAVerifiedActorEveryEndpointAnswers401NeverValidationDetail() throws Exception {
        actor.actAsNobody();
        for (Call call : invalidCalls()) {
            assertOnlyAFixedProblem(run(call), 401);
        }
    }

    @Test
    void waiterAndKitchenGet403NeverValidationDetail() throws Exception {
        for (ActorRole role : new ActorRole[] {ActorRole.WAITER, ActorRole.KITCHEN}) {
            actor.actAs(RESTAURANT, role);
            for (Call call : invalidCalls()) {
                assertOnlyAFixedProblem(run(call), 403);
            }
        }
    }

    @Test
    void anAdminStillGetsFullValidationFeedbackForTheSameInputs() throws Exception {
        actor.actAs(RESTAURANT, ActorRole.ADMIN);
        for (Call call : invalidCalls()) {
            MvcResult result = run(call);
            assertEquals(400, result.getResponse().getStatus(), call + " -> " + result.getResponse().getContentAsString());
        }
        MvcResult validation = run(new Call(HttpMethod.POST, P, "{}"));
        assertNotNull(JsonPath.read(validation.getResponse().getContentAsString(), "$.errors"), "an ADMIN still sees which fields are wrong");
    }

    @Test
    void withoutAnyFrameworkAuthenticationTheAnswerIsStill401() throws Exception {
        actor.actAs(RESTAURANT, ActorRole.ADMIN);
        for (Call call : invalidCalls()) {
            var builder = request(call.method(), call.path()).with(csrf());
            if (call.body() != null) {
                builder.contentType(MediaType.APPLICATION_JSON).content(call.body());
            }
            assertEquals(401, mvc.perform(builder).andReturn().getResponse().getStatus(), call.toString());
        }
    }

    @Test
    void identityHeadersAndBodyValuesDoNotAuthenticateOrAuthorize() throws Exception {
        actor.actAsNobody();
        MvcResult result = mvc.perform(request(HttpMethod.POST, P).with(user("staff")).with(csrf())
                .header("X-Restaurant-Id", RESTAURANT).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"restaurantId\":\"" + RESTAURANT + "\",\"role\":\"ADMIN\"}")).andReturn();
        assertOnlyAFixedProblem(result, 401);
        actor.actAs(RESTAURANT, ActorRole.WAITER);
        MvcResult waiter = mvc.perform(request(HttpMethod.POST, P).with(user("staff")).with(csrf())
                .header("X-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertOnlyAFixedProblem(waiter, 403);
    }
}
