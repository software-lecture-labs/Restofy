package com.restofy.auth.infrastructure.security;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.restofy.auth.infrastructure.client.MailClient;
import com.restofy.auth.infrastructure.config.AuthMailSettings;
import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.infrastructure.persistence.repository.MailOutboxRepository;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailClientTests {
    private final KeyPair sessionKeys = AuthSessionTestSupport.generateKeys();
    private final KeyPair mailKeys = AuthSessionTestSupport.generateKeys();

    @Test
    void enqueueWaitsForTheRelayAndHttpUsesTheDedicatedMailKey() throws Exception {
        var tokens = new CopyOnWriteArrayList<String>();
        var bodies = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/api/v1/mail/password-reset-jobs", exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("Authorization").substring("Bearer ".length()));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        var env = environment("http://127.0.0.1:" + server.getAddress().getPort());
        MailOutboxRepository outbox = mock(MailOutboxRepository.class);
        try (var client = new MailClient(settings(env), env, Clock.systemUTC(), outbox)) {
            var message = message(Instant.now().plusSeconds(60));
            client.enqueue(message);
            verify(outbox).enqueue(message);
            assertThat(tokens).isEmpty();
            client.deliver(message);
            assertThat(tokens).hasSize(1);
            var claims = Jwts.parser().verifyWith((RSAPublicKey) mailKeys.getPublic()).requireAudience("restofy-mail")
                    .requireIssuer("restofy-auth").require("token_type", "service").require("scope", "password-reset:send")
                    .build().parseSignedClaims(tokens.getFirst()).getPayload();
            assertThat(claims.getSubject()).isEqualTo("auth-service");
            assertThat(claims.getId()).isEqualTo(message.messageId().toString());
            assertThat(claims.getExpiration().toInstant()).isEqualTo(claims.getIssuedAt().toInstant().plusSeconds(60));
            assertThatThrownBy(() -> Jwts.parser().verifyWith((RSAPublicKey) sessionKeys.getPublic())
                    .build().parseSignedClaims(tokens.getFirst())).isInstanceOf(RuntimeException.class);
            assertThat(bodies.getFirst()).contains("\"code\":\"123456\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void transportFailurePropagatesAndExpiredCommandsNeverLeaveTheProcess() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/api/v1/mail/password-reset-jobs", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        var env = environment("http://127.0.0.1:" + server.getAddress().getPort());
        try (var client = new MailClient(settings(env), env, Clock.systemUTC(), mock(MailOutboxRepository.class))) {
            assertThatThrownBy(() -> client.deliver(message(Instant.now().plusSeconds(60)))).isInstanceOf(RuntimeException.class);
            assertThat(requests).hasValue(1);
            assertThatThrownBy(() -> client.deliver(message(Instant.now().minusSeconds(1)))).isInstanceOf(IllegalStateException.class);
            assertThat(requests).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reusedSessionSigningKeyAndInvalidEncryptionMaterialFailStartupWithoutValues() throws Exception {
        var env = environment("http://127.0.0.1:9");
        env.withProperty("auth.mail.private-key", Base64.getEncoder().encodeToString(sessionKeys.getPrivate().getEncoded()));
        assertThatThrownBy(() -> settings(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_MAIL_PRIVATE_KEY").hasNoCause();
        env.withProperty("auth.mail.private-key", "invalid-key-material");
        assertThatThrownBy(() -> settings(env)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("invalid-key-material").hasNoCause();
        var badEncryption = environment("http://127.0.0.1:9");
        badEncryption.withProperty("auth.mail.outbox-key", Base64.getEncoder().encodeToString(new byte[32]));
        assertThatThrownBy(() -> settings(badEncryption)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_MAIL_OUTBOX_KEY").hasNoCause();
    }

    private AuthMailSettings settings(MockEnvironment env) {
        AuthSettings sessions = mock(AuthSettings.class);
        when(sessions.publicKey()).thenReturn((RSAPublicKey) sessionKeys.getPublic());
        when(sessions.issuer()).thenReturn("restofy-auth");
        return new AuthMailSettings(sessions, env);
    }

    private MockEnvironment environment(String url) throws Exception {
        byte[] encryption = new byte[32];
        new SecureRandom().nextBytes(encryption);
        var env = new MockEnvironment().withProperty("auth.reset.mail-url", url)
                .withProperty("auth.mail.private-key", Base64.getEncoder().encodeToString(mailKeys.getPrivate().getEncoded()))
                .withProperty("auth.mail.outbox-key", Base64.getEncoder().encodeToString(encryption));
        env.setActiveProfiles("test");
        var properties = new Properties();
        try (var input = new ClassPathResource("mail-service-contract.properties").getInputStream()) {
            properties.load(input);
        }
        properties.forEach((key, value) -> env.withProperty(key.toString(), value.toString()));
        return env;
    }

    private static MailClient.Message message(Instant deadline) {
        return new MailClient.Message(UUID.randomUUID(), "ADMIN_PASSWORD_RESET", "admin@example.test", "tr", "123456",
                Instant.now().plusSeconds(900), deadline);
    }
}
