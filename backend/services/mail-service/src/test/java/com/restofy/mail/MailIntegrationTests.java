package com.restofy.mail;

import java.time.*;
import java.util.*;
import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.application.service.*;
import com.restofy.mail.infrastructure.client.SmtpMailSender;
import com.restofy.mail.infrastructure.persistence.repository.MailJobRepository;
import com.restofy.mail.infrastructure.security.*;
import com.restofy.mail.support.*;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@ContextConfiguration(initializers=MailTestDatabaseInitializer.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class MailIntegrationTests extends MailTestSupport {
    @Autowired private MockMvc mvc;
    @Autowired private MailJobService service;
    @Autowired private MailJobRepository jobs;
    @Autowired private MailServiceTokens tokens;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private SmtpMailSender smtp;
    private static final String PATH=MailSecurityConfiguration.JOB_PATH;
    @BeforeEach void clean() { jdbc.update("DELETE FROM mail_delivery_attempts"); jdbc.update("DELETE FROM mail_jobs"); }
    private PasswordResetMailRequest message(UUID id) {
        return new PasswordResetMailRequest(id,"ADMIN_PASSWORD_RESET","admin@example.test","en","123456",Instant.now().plusSeconds(900),Instant.now().plusSeconds(100));
    }
    private String token(UUID id) { return token(id,"restofy-mail","service","password-reset:send",60,"restofy-auth",KEYS.getPrivate()); }
    private String token(UUID id,String audience,String type,String scope,int ttl,String issuer,java.security.PrivateKey key) {
        Instant now=Instant.now();
        return Jwts.builder().issuer(issuer).subject("auth-service").id(id.toString()).audience().add(audience).and()
                .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(now.plusSeconds(ttl)))
                .claim("token_type",type).claim("scope",scope).signWith(key,Jwts.SIG.RS256).compact();
    }
    private String body(UUID id) {
        return "{\"messageId\":\""+id+"\",\"templateKey\":\"ADMIN_PASSWORD_RESET\",\"recipient\":\"admin@example.test\",\"locale\":\"en\",\"code\":\"123456\",\"codeExpiresAt\":\""+Instant.now().plusSeconds(900)+"\",\"sendBefore\":\""+Instant.now().plusSeconds(100)+"\"}";
    }
    private void accepted(UUID id) { await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jobs.find(id).orElseThrow().status()).isEqualTo("SMTP_ACCEPTED")); }

    @Test void validServiceIdentityCreatesJobAndSendsOnceWithoutPersistingPayload() throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(post(PATH).header("Authorization","Bearer "+token(id)).contentType(MediaType.APPLICATION_JSON).content(body(id)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value(id.toString()))
                .andExpect(header().string("Cache-Control","no-store"));
        accepted(id);
        mvc.perform(post(PATH).header("Authorization","Bearer "+token(id)).contentType(MediaType.APPLICATION_JSON)
                .content(body(id).replace("admin@example.test","different@example.test").replace("123456","654321")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("SMTP_ACCEPTED"));
        verify(smtp,times(1)).send(any());
        assertThat(jdbc.queryForObject("SELECT row_to_json(j)::text FROM mail_jobs j",String.class)).doesNotContain("123456","admin@","recipient","code_digest","template_data");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_delivery_attempts",Integer.class)).isEqualTo(1);
    }

    @Test void missingForgedAndUserIdentitiesAreRejected() throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(id)).header("X-Role","ADMIN").header("X-User-Id","auth-service"))
                .andExpect(status().isUnauthorized());
        for (String invalid:List.of("invalid",token(id,"restofy-staff-api","access","password-reset:send",60,"restofy-auth",KEYS.getPrivate()),
                token(id,"restofy-mail","service","other",60,"restofy-auth",KEYS.getPrivate()),
                token(id,"restofy-mail","service","password-reset:send",120,"restofy-auth",KEYS.getPrivate()),
                token(id,"restofy-mail","service","password-reset:send",-1,"restofy-auth",KEYS.getPrivate()),
                token(id,"restofy-mail","service","password-reset:send",60,"other",KEYS.getPrivate()))) {
            mvc.perform(post(PATH).header("Authorization","Bearer "+invalid).contentType(MediaType.APPLICATION_JSON).content(body(id)))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_jobs",Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"/api/v1/mail","/api/v1/mail/messages","/api/v1/mail/password-reset-jobs","/actuator/info","/actuator/env","/actuator/health/other","/internal/api/v1/mail/other"})
    void allOtherPathsStayDeniedEvenWithServiceJwt(String path) throws Exception {
        mvc.perform(get(path).header("Authorization","Bearer "+token(UUID.randomUUID())))
                .andExpect(status().is4xxClientError());
        mvc.perform(post(path).header("Authorization","Bearer "+token(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @ParameterizedTest @ValueSource(strings={"/actuator/health","/actuator/health/liveness","/actuator/health/readiness"})
    void onlyThreeAnonymousHealthGetsAreAvailableWithoutDetails(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(post(path)).andExpect(status().is4xxClientError());
    }

    @Test void tokenCannotBeReusedToCreateDifferentMessageAndDtosAreValidated() throws Exception {
        UUID id=UUID.randomUUID();
        mvc.perform(post(PATH).header("Authorization","Bearer "+token(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON).content(body(id)))
                .andExpect(status().isForbidden());
        mvc.perform(post(PATH).header("Authorization","Bearer "+token(id)).contentType(MediaType.APPLICATION_JSON).content(body(id).replace("123456","12345")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("Authorization","Bearer "+token(id)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void unknownSmtpResultIsNeverRetried(org.springframework.boot.test.system.CapturedOutput output) {
        doThrow(new org.springframework.mail.MailSendException("test",new java.net.SocketTimeoutException("test"))).when(smtp).send(any());
        UUID id=UUID.randomUUID(); service.accept(message(id));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jobs.find(id).orElseThrow().status()).isEqualTo("UNKNOWN"));
        verify(smtp,times(1)).send(any());
        assertThat(output.getAll()).doesNotContain("123456","admin@example.test");
    }

    @Test void definiteConnectionFailureRetriesAtMostThreeTimes() {
        doThrow(new org.springframework.mail.MailSendException("test",new java.net.ConnectException("test"))).when(smtp).send(any());
        UUID id=UUID.randomUUID(); service.accept(message(id));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jobs.find(id).orElseThrow().status()).isEqualTo("FAILED"));
        verify(smtp,times(3)).send(any());
        assertThat(jobs.find(id).orElseThrow().attempts()).isEqualTo(3);
    }

    @Test void expiredPayloadMetadataAndUnfinishedAttemptsBecomeSafeTerminalStates() {
        UUID pending=UUID.randomUUID(); UUID sending=UUID.randomUUID();
        for (UUID id:List.of(pending,sending)) { jdbc.update("INSERT INTO mail_jobs (id,template_key,locale,status,created_at,send_before) VALUES (?,'ADMIN_PASSWORD_RESET','en','PENDING',now()-interval '3 minutes',now()-interval '1 minute')",id); }
        jdbc.update("UPDATE mail_jobs SET status='SENDING',attempt_count=1 WHERE id=?",sending);
        jdbc.update("INSERT INTO mail_delivery_attempts VALUES (?,?,1,now(),'SENDING',NULL)",UUID.randomUUID(),sending);
        jobs.expire();
        assertThat(jobs.find(pending).orElseThrow().status()).isEqualTo("PAYLOAD_LOST");
        assertThat(jobs.find(sending).orElseThrow().status()).isEqualTo("UNKNOWN");
        assertThat(jobs.claim(pending)).isZero(); assertThat(jobs.claim(sending)).isZero();
    }

    @Test void concurrentDuplicateCommandsCreateOnlyOneJobAndOneSmtpAttempt() throws Exception {
        UUID id=UUID.randomUUID(); var message=message(id);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{gate.await();return service.accept(message);});
            var second=pool.submit(()->{gate.await();return service.accept(message);});
            gate.countDown();
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS).jobId()).isEqualTo(id);
            assertThat(second.get(10,java.util.concurrent.TimeUnit.SECONDS).jobId()).isEqualTo(id);
        }
        accepted(id);verify(smtp,times(1)).send(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_jobs",Integer.class)).isEqualTo(1);
    }

    @Test void invalidDeadlineAndDatabaseConstraintsFailClosed() {
        UUID id=UUID.randomUUID();
        var stale=new PasswordResetMailRequest(id,"PASSWORD_CHANGED","admin@example.test","en",null,null,Instant.now().minusSeconds(1));
        assertThatThrownBy(()->service.accept(stale)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO mail_jobs (id,template_key,locale,status,created_at,send_before) VALUES (?,'ARBITRARY','en','PENDING',now(),now()+interval '1 minute')",id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
