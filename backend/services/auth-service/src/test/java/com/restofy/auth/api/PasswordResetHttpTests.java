package com.restofy.auth.api;

import java.util.UUID;

import com.restofy.auth.infrastructure.client.MailClient;
import com.restofy.auth.infrastructure.security.AuthCookies;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"auth.bootstrap.dev-users.enabled=false","auth.bootstrap.initial-admin.enabled=false"})
@ContextConfiguration(initializers=AuthTestDatabaseInitializer.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class PasswordResetHttpTests extends AuthSessionTestSupport {
    private static final String ROOT="/api/v1/auth/password-reset";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private MailClient mail;
    private Cookie csrf;
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM password_reset_limits");
        csrf=mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse().getCookie(AuthCookies.CSRF);
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(String path,String body) {
        return post(ROOT+path).header("Origin","http://localhost:5000").cookie(csrf)
                .header("X-CSRF-TOKEN",csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content(body);
    }
    @Test void unknownAccountsReturnGenericNoStoreResponseAndUnusableHttpOnlyCookie() throws Exception {
        var result=mvc.perform(postJson("/request","{\"email\":\"missing@example.com\",\"totpCode\":\"123456\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("REQUEST_ACCEPTED"))
                .andExpect(header().string("Cache-Control","no-store")).andReturn();
        var cookie=result.getResponse().getCookie("restofy_reset");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo(ROOT);
        assertThat(cookie.getValue()).hasSize(43);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("missing@","123456");
        mvc.perform(postJson("/request","{\"email\":\"missing@example.com\",\"totpCode\":\"123456\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
    @ParameterizedTest @ValueSource(strings={"request","verify","confirm"})
    void csrfAndTrustedOriginAreRequiredEvenBeforeLogin(String path) throws Exception {
        mvc.perform(post(ROOT+"/"+path).header("Origin","http://localhost:5000").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(ROOT+"/"+path).header("Origin","https://evil.example").cookie(csrf)
                .header("X-CSRF-TOKEN",csrf.getValue()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
    @ParameterizedTest @ValueSource(strings={"bad","x@bad","a..b@example.com"})
    void invalidEmailIsBadRequest(String email) throws Exception {
        mvc.perform(postJson("/request","{\"email\":\""+email+"\",\"totpCode\":\"123456\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void malformedCodesAndForgedHeadersCannotSkipSteps() throws Exception {
        mvc.perform(postJson("/request","{\"email\":\"a@example.com\",\"totpCode\":\"１２３４５６\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(postJson("/verify","{\"code\":\"123456\"}").header("X-Role","ADMIN").header("X-User-Id",UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(postJson("/confirm","{\"newPassword\":\"A-valid-test-password-01\"}"))
                .andExpect(status().isUnauthorized());
    }
}
