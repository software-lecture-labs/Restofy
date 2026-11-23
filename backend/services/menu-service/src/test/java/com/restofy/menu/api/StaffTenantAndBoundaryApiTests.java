package com.restofy.menu.api;

import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.security.TrustedKitchenCallerProvider;
import com.restofy.menu.application.service.KitchenAvailabilityCommand;
import com.restofy.menu.application.service.KitchenAvailabilityCommandService;
import com.restofy.menu.domain.enums.ActorRole;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.support.TestPublicScopeConfiguration;
import com.restofy.menu.support.TestStaffTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static com.restofy.menu.support.StaffRequests.accessCookie;
import static com.restofy.menu.support.StaffRequests.csrf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where the verified identity ends: another restaurant's data, a forged restaurant or role, the public menu, internal
 * service callers and Auth being unreachable. All of it runs on the real staff chain (see AbstractStaffAuthApiTest).
 */
class StaffTenantAndBoundaryApiTests extends AbstractStaffAuthApiTest {

    @Autowired KitchenAvailabilityCommandService kitchen;
    @Autowired TrustedKitchenCallerProvider callers;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- another restaurant ----

    @Test
    void anAdminOfAnotherRestaurantNeitherSeesNorChangesAnything() throws Exception {
        UUID category = createCategory(adminA(), "Main");
        UUID product = createProduct(adminA(), category, "Pide");
        String b = adminB();

        read(CATEGORIES + "/" + category, b).andExpect(status().isNotFound());
        read(PRODUCTS + "/" + product, b).andExpect(status().isNotFound());
        read(PRODUCTS + "/" + product + "/options", b).andExpect(status().isNotFound());
        read(CATEGORIES, b).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        read(PRODUCTS, b).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        mutate(patch(PRODUCTS + "/" + product), b, "{\"name\":\"Hacked\",\"version\":0}").andExpect(status().isNotFound());
        mutate(patch(CATEGORIES + "/" + category), b, "{\"name\":\"Hacked\",\"version\":0}").andExpect(status().isNotFound());
        mutate(patch(PRODUCTS + "/" + product + "/availability"), b, "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"X\",\"version\":0}")
                .andExpect(status().isNotFound());
        mutate(patch(PRODUCTS + "/" + product + "/publication"), b, "{\"isPublished\":true,\"version\":0}").andExpect(status().isNotFound());
        mutate(post(PRODUCTS + "/" + product + "/options"), b, "{\"type\":\"INCLUDED\",\"name\":\"X\",\"removable\":true}")
                .andExpect(status().isNotFound());
        mutate(post(CATEGORIES + "/" + category + "/subcategories"), b, "{\"name\":\"X\"}").andExpect(status().isNotFound());
        mutate(post(PRODUCTS), b, "{\"categoryId\":\"" + category + "\",\"name\":\"Stolen\",\"basePrice\":1,\"currency\":\"TRY\","
                + "\"vatRate\":10,\"priceIncludesVat\":true}").andExpect(status().is4xxClientError());

        assertEquals("Main", jdbc.queryForObject("SELECT name FROM categories WHERE id = ?", String.class, category));
        assertEquals("Pide", jdbc.queryForObject("SELECT name FROM products WHERE id = ?", String.class, product));
        assertEquals(1, count("products"));
        assertEquals(1, count("categories"));
        assertEquals(0, count("product_options"));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM product_availability WHERE product_id = ?", String.class, product));
    }

    @Test
    void theSameRequestsSucceedForTheOwningRestaurant() throws Exception {
        UUID category = createCategory(adminA(), "Main");
        UUID product = createProduct(adminA(), category, "Pide");
        read(CATEGORIES + "/" + category, adminA()).andExpect(status().isOk());
        read(PRODUCTS + "/" + product, adminA()).andExpect(status().isOk());
        read(CATEGORIES, adminB()).andExpect(jsonPath("$.totalElements").value(0));
        UUID other = createCategory(adminB(), "Main");
        read(CATEGORIES, adminA()).andExpect(jsonPath("$.totalElements").value(1));
        read(CATEGORIES, adminB()).andExpect(jsonPath("$.totalElements").value(1));
        assertEquals(RESTAURANT_B, jdbc.queryForObject("SELECT restaurant_id FROM categories WHERE id = ?", UUID.class, other));
    }

    // ---- forged restaurant, user or role ----

    @Test
    void aClientSuppliedRestaurantOrRoleIsNeverEvidenceOfAnything() throws Exception {
        UUID category = createCategory(adminA(), "Main");
        String forgedHeaders = "X-Restaurant-Id";
        // A waiter claims to be an admin of the other restaurant in every channel at once.
        String waiter = token(ActorRole.WAITER, RESTAURANT_A);
        MvcResult forbidden = mutate(post(CATEGORIES + "?restaurantId=" + RESTAURANT_B + "&role=ADMIN&userId=" + UUID.randomUUID())
                .header(forgedHeaders, RESTAURANT_B).header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID())
                .header("X-Service-Name", "kitchen-service"), waiter,
                "{\"name\":\"Forged\",\"restaurantId\":\"" + RESTAURANT_B + "\",\"role\":\"ADMIN\",\"userId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isForbidden()).andReturn();
        assertTrue(forbidden.getResponse().getContentAsString().contains("not allowed"));
        // Without any credential the same headers change nothing either.
        mutate(post(CATEGORIES).header(forgedHeaders, RESTAURANT_A).header("X-Role", "ADMIN"), null, "{\"name\":\"Forged\"}")
                .andExpect(status().isUnauthorized());
        assertEquals(1, count("categories"));

        // An admin of A that names restaurant B in the body, the query and the headers still works in A only.
        mutate(post(CATEGORIES + "?restaurantId=" + RESTAURANT_B).header(forgedHeaders, RESTAURANT_B).header("X-Role", "ADMIN"), adminA(),
                "{\"name\":\"Mine\",\"restaurantId\":\"" + RESTAURANT_B + "\"}").andExpect(status().isCreated());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM categories WHERE restaurant_id = ?", Integer.class, RESTAURANT_B));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM categories WHERE restaurant_id = ?", Integer.class, RESTAURANT_A));
        read(CATEGORIES + "/" + category + "?restaurantId=" + RESTAURANT_B, adminB()).andExpect(status().isNotFound());
        mvcGetWithForgedHeaders(adminB(), category).andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions mvcGetWithForgedHeaders(String token, UUID category) throws Exception {
        return mvc.perform(get(CATEGORIES + "/" + category).with(accessCookie(token)).header("X-Restaurant-Id", RESTAURANT_A)
                .header("X-Role", "ADMIN"));
    }

    @Test
    void theUserWhoMadeAChangeIsTheOneInTheToken() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = TestStaffTokens.access(userId, RESTAURANT_A, ActorRole.ADMIN).build();
        UUID category = createCategory(token, "Main");
        UUID product = createProduct(token, category, "Pide");
        mutate(patch(PRODUCTS + "/" + product + "/availability").header("X-User-Id", UUID.randomUUID()), token,
                "{\"status\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\",\"version\":0}").andExpect(status().isOk());
        assertEquals(userId, jdbc.queryForObject("SELECT changed_by_user_id FROM product_availability WHERE product_id = ?", UUID.class,
                product));
    }

    // ---- the public menu ----

    @Test
    void thePublicMenuNeedsNoStaffCredentialAndGrantsNone() throws Exception {
        UUID category = createCategory(adminA(), "Main");
        String publicPath = "/api/v1/menu/public/restaurants/" + TestPublicScopeConfiguration.ALPHA_REF + "/categories";
        String anonymous = read(publicPath, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertNull(read(publicPath, null).andReturn().getResponse().getHeader("Set-Cookie"));

        // A staff cookie, a broken one and a foreign one change nothing about what the public path answers or allows.
        for (String token : new String[] {adminA(), adminB(), "garbage", TestStaffTokens.admin(RESTAURANT_A).lifetime(400, 300).build()}) {
            assertEquals(anonymous, read(publicPath, token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            mutate(post(publicPath), token, "{\"name\":\"x\"}").andExpect(status().isForbidden());
            mutate(patch(publicPath), token, "{\"name\":\"x\"}").andExpect(status().isForbidden());
            mutate(put(publicPath), token, "{\"name\":\"x\"}").andExpect(status().isForbidden());
        }
        // Reading the public menu opened nothing on the management side.
        read(CATEGORIES + "/" + category, null).andExpect(status().isUnauthorized());
        read(CATEGORIES, null).andExpect(status().isUnauthorized());
        mutate(post(CATEGORIES), null, "{\"name\":\"x\"}").andExpect(status().isUnauthorized());
        assertEquals(1, count("categories"));
    }

    @Test
    void aPublicReaderCannotUseTheCustomerPathToReachManagementData() throws Exception {
        UUID category = createCategory(adminA(), "Main");
        UUID product = createProduct(adminA(), category, "Pide");
        for (String path : new String[] {"/api/v1/menu/public/../categories", "/api/v1/menu/public/%2e%2e/products",
                "/api/v1/menu/publicx/restaurants/x", "/api/v1/menu/PUBLIC/restaurants/alpha-menu/categories"}) {
            int status = read(path, null).andReturn().getResponse().getStatus();
            assertTrue(status == 400 || status == 401 || status == 403 || status == 404, path + " answered " + status);
        }
        read(PRODUCTS + "/" + product, null).andExpect(status().isUnauthorized());
    }

    // ---- internal service callers ----

    @Test
    void noStaffCredentialIsAKitchenServiceIdentityAndThereIsNoInternalRoute() throws Exception {
        UUID category = createCategory(adminA(), "Main");
        UUID product = createProduct(adminA(), category, "Pide");
        String before = jdbc.queryForObject("SELECT status || '/' || version FROM product_availability WHERE product_id = ?", String.class,
                product);
        String body = "{\"commandId\":\"" + UUID.randomUUID() + "\",\"productId\":\"" + product
                + "\",\"targetStatus\":\"OUT_OF_STOCK\",\"reasonCode\":\"SOLD_OUT\"}";
        for (ActorRole role : ActorRole.values()) {
            String token = token(role, RESTAURANT_A);
            for (String path : new String[] {"/internal/v1/menu/availability-commands", "/api/v1/internal/menu/availability-commands",
                    "/api/v1/menu/internal/availability-commands", "/api/v1/menu/availability-commands", "/api/v1/menu/kitchen/availability"}) {
                for (var request : List.of(post(path), put(path), patch(path))) {
                    int status = mvc.perform(request.with(csrf()).with(accessCookie(token)).header("X-Service-Name", "kitchen-service")
                            .header("X-Role", "KITCHEN").header("X-Restaurant-Id", RESTAURANT_A).contentType(MediaType.APPLICATION_JSON)
                            .content(body)).andReturn().getResponse().getStatus();
                    assertTrue(status == 401 || status == 403 || status == 404 || status == 405, path + " answered " + status);
                }
            }
        }
        assertEquals(before, jdbc.queryForObject("SELECT status || '/' || version FROM product_availability WHERE product_id = ?",
                String.class, product));
        assertEquals(0, count("product_availability_commands"));
    }

    @Test
    void aVerifiedStaffMemberInTheRequestContextIsNotAVerifiedKitchenCaller() {
        UUID product = UUID.randomUUID();
        for (ActorRole role : ActorRole.values()) {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new AuthenticatedActor(UUID.randomUUID(), RESTAURANT_A, role), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
            SecurityContextHolder.setContext(context);
            assertTrue(callers.current().isEmpty(), "a staff identity is a person, never the Kitchen service: " + role);
            var command = new KitchenAvailabilityCommand(UUID.randomUUID(), product, AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null, null);
            assertThrows(UnauthenticatedException.class, () -> kitchen.handle(command));
        }
        assertEquals(0, count("product_availability_commands"));
    }

    @Test
    void aStaffCookieOpensNothingOutsideTheManagementApi() throws Exception {
        String admin = adminA();
        for (String path : new String[] {"/actuator/info", "/actuator/env", "/internal/v1/menu/products", "/api/v1/other", "/nothing", "/",
                "/error"}) {
            assertEquals(401, read(path, admin).andReturn().getResponse().getStatus(), path);
        }
        read("/actuator/health", null).andExpect(status().isOk());
    }

    // ---- Auth is not called, so Auth cannot be the weak link ----

    @Test
    void verificationNeedsNoNetworkCallToAuthSoAnOutageOfAuthDoesNotOpenOrBlockAnything() throws Exception {
        // The only inputs are the signed token and the configured public key: nothing resolves a host, nothing times out.
        // Valid tokens keep working while Auth is down (its access lifetime bounds them); tokens that cannot be proved
        // stay refused, whatever Auth does.
        read(CATEGORIES, adminA()).andExpect(status().isOk());
        read(CATEGORIES, TestStaffTokens.admin(RESTAURANT_A).signedByAStranger().build()).andExpect(status().isUnauthorized());
        read(CATEGORIES, null).andExpect(status().isUnauthorized());
        for (var field : com.restofy.menu.infrastructure.security.StaffAuthProperties.class.getRecordComponents()) {
            assertTrue(List.of("staffPublicKey", "issuer", "allowedOrigins").contains(field.getName()),
                    "no Auth URL or client setting exists: " + field.getName());
        }
    }

    @Test
    void theMfaPendingCookiePathOfAuthIsNotWhatMenuReads() throws Exception {
        String pending = TestStaffTokens.admin(RESTAURANT_A).tokenType("mfa_pending").authState("MFA_PENDING")
                .audience("restofy-auth-mfa").build();
        mvc.perform(get(CATEGORIES).cookie(new Cookie("restofy_mfa", pending))).andExpect(status().isUnauthorized());
        mvc.perform(get(CATEGORIES).cookie(new Cookie("restofy_access", pending))).andExpect(status().isUnauthorized());
    }
}
