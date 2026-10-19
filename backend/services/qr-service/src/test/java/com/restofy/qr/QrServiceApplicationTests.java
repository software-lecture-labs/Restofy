package com.restofy.qr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
class QrServiceApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired com.restofy.qr.application.service.OrderAdmissionService admissions;
    @Autowired com.restofy.qr.application.service.GuestSessionContextReader contexts;
    @Autowired com.restofy.qr.application.service.OrderBusinessOutcomeService outcomes;

    @Test
    void aPaymentReferenceCannotSupplyTheMissingOrderAndPaymentAuthority() {
        var id = java.util.UUID.randomUUID();
        var command = new com.restofy.qr.domain.OrderOutcome.Command(id, id, id, 1, 0,
                com.restofy.qr.domain.enums.OrderBusinessStatus.COMPLETED, java.time.Instant.now(), id);
        assertThatThrownBy(() -> outcomes.apply(command)).isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void lockedContextsRequireTheCallersMutationTransaction() {
        var id = java.util.UUID.randomUUID();
        assertThatThrownBy(() -> contexts.lockSession(id, id)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void defaultAdminRoleCannotSupplyOrderAuthorityEvenForInternalCommands() throws Exception {
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                "test-staff", "unused-test-credential", java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            var id = java.util.UUID.randomUUID();
            var request = new com.restofy.qr.application.service.OrderAdmissionService.Reservation(id, id, id, id, "key", "a".repeat(64), "A".repeat(43));
            assertThatThrownBy(() -> admissions.reserve(request)).isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        mvc.perform(post("/api/v1/qr/order-admissions").with(csrf()).header("X-Role", "ORDER_SERVICE")
                .header("X-Restaurant-Id", "forged")).andExpect(status().isUnauthorized());
    }

    @Test
    void explicitGuestActivityStaysClosedBeforeReviewedBrowserAndAuthIntegration() throws Exception {
        String path = "/api/v1/qr/guest-sessions/activity";
        mvc.perform(post(path).with(csrf()).header("X-Restaurant-Id", "forged").header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(csrf()).with(user("test-staff").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void statisticsRemainsClosedForAnonymousForgedAndUnverifiedStaffRequests() throws Exception {
        String path = "/api/v1/qr/tables/00000000-0000-0000-0000-000000000001/session-statistics";
        mvc.perform(get(path).header("X-Restaurant-Id", "forged").header("X-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user("test-staff").roles("ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void guestCredentialDoesNotOpenHttpOrGrantStaffAuthority() throws Exception {
        String token = "A".repeat(43);
        mvc.perform(post("/api/v1/qr/table-sessions/open").with(csrf())
                .header("Authorization", "Bearer " + token).header("X-Guest-Session-Id", "forged")
                .header("X-Restaurant-Id", "forged").header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"qrToken\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/qr/tables").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousAndForgedTenantHeadersCannotReadTables() throws Exception {
        mvc.perform(get("/api/v1/qr/tables").header("X-Restaurant-Id", "arbitrary")
                .header("X-Role", "ADMIN").header("X-User-Id", "arbitrary"))
                .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void authenticatedDefaultUserCannotManageTables() throws Exception {
        mvc.perform(get("/api/v1/qr/tables").with(user("test-staff").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/qr/tables").with(user("test-staff").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tableNumber\":\"1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void missingCsrfCannotWriteAndSwaggerRemainsClosed() throws Exception {
        mvc.perform(post("/api/v1/qr/tables").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tableNumber\":\"1\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsAvailableWithoutExposingDetails() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP")).andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void qrCreationRemainsClosedWithForgeryDefaultRolesAndMissingCsrf() throws Exception {
        String path = "/api/v1/qr/tables/00000000-0000-0000-0000-000000000001/qr-codes";
        mvc.perform(post(path).with(csrf()).header("X-Restaurant-Id", "forged").header("X-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(csrf()).with(user("test-staff").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post(path)).andExpect(status().isForbidden());
    }

    @Test
    void qrRotationCannotBeOpenedByForgedHeadersDefaultRolesOrMissingCsrf() throws Exception {
        String path = "/api/v1/qr/tables/00000000-0000-0000-0000-000000000001/qr-codes/00000000-0000-0000-0000-000000000002/rotate";
        mvc.perform(post(path).with(csrf()).header("X-Restaurant-Id", "forged").header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(csrf()).with(user("test-staff").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "DELETE"})
    void qrLifecycleStaysClosedWithForgedContextDefaultRolesAndMissingCsrf(String method) throws Exception {
        String base = "/api/v1/qr/tables/00000000-0000-0000-0000-000000000001/qr-codes/00000000-0000-0000-0000-000000000002";
        String path = method.equals("PATCH") ? base + "/active" : base;
        mvc.perform(request(HttpMethod.valueOf(method), path).queryParam("version", "0").with(csrf())
                .header("X-Restaurant-Id", "forged").header("X-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false,\"version\":0}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(request(HttpMethod.valueOf(method), path).queryParam("version", "0").with(csrf())
                .with(user("test-staff").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"isActive\":false,\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(request(HttpMethod.valueOf(method), path).queryParam("version", "0"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/image", "/download", "/list"})
    void qrMetadataCannotBeReadWithForgedHeadersOrAnUnverifiedAdminRole(String suffix) throws Exception {
        String path = "/api/v1/qr/tables/00000000-0000-0000-0000-000000000001/qr-codes/00000000-0000-0000-0000-000000000002" + suffix;
        if (suffix.equals("/list")) {
            path = "/api/v1/qr/tables/00000000-0000-0000-0000-000000000001/qr-codes";
        }
        mvc.perform(get(path).header("X-Restaurant-Id", "forged").header("X-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user("test-staff").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

	@Test
	void contextLoadsThroughMain() {
	    assertThat(mvc).isNotNull();
	}

    @Test
    void qrValidationCannotGrantPublicOrStaffAccessBeforeReviewedIntegration() throws Exception {
        String body = "{\"qrToken\":\"" + "A".repeat(43) + "\"}";
        mvc.perform(post("/api/v1/qr/validate").with(csrf()).header("X-Restaurant-Id", "forged")
                .header("X-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/qr/validate").with(csrf()).with(user("test-staff").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/qr/validate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void mainSessionOpeningStaysClosedForAnonymousForgedAndUnverifiedStaffRequests() throws Exception {
        String body = "{\"qrToken\":\"" + "A".repeat(43) + "\"}";
        mvc.perform(post("/api/v1/qr/table-sessions/open").with(csrf()).header("X-Restaurant-Id", "forged")
                .header("X-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/qr/table-sessions/open").with(csrf()).with(user("test-staff").roles("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/qr/table-sessions/open").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

}
