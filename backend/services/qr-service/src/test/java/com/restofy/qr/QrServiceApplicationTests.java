package com.restofy.qr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = "qr.payload.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
@AutoConfigureMockMvc
class QrServiceApplicationTests {
    @Autowired MockMvc mvc;

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

	@Test
	void contextLoadsThroughMain() {
	    assertThat(mvc).isNotNull();
	}

}
