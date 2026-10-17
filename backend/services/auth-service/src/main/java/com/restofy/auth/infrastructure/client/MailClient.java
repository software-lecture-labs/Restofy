package com.restofy.auth.infrastructure.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import com.restofy.auth.infrastructure.config.AuthSettings;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

@Component
@PropertySource("classpath:mail-service-contract.properties")
public class MailClient {
    private static final Logger LOG = LoggerFactory.getLogger(MailClient.class);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), Thread.ofPlatform().name("auth-mail-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final AuthSettings settings;
    private final Environment environment;
    private final Clock clock;
    private final HttpClient http;
    private final RestClient client;

    public MailClient(AuthSettings settings, Environment environment, Clock clock) {
        this.settings = settings; this.environment = environment; this.clock = clock;
        URI base = URI.create(environment.getRequiredProperty("auth.reset.mail-url"));
        boolean development = environment.getActiveProfiles().length > 0 && java.util.Set.of("dev", "local", "test")
                .containsAll(java.util.List.of(environment.getActiveProfiles()));
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !java.util.Set.of("http", "https").contains(base.getScheme()) || (!development && !"https".equals(base.getScheme()))) {
            throw new IllegalStateException("Invalid Mail service address");
        }
        int connect = environment.getProperty("REST_CLIENT_CONNECT_TIMEOUT_MS", Integer.class, 3000);
        int read = environment.getProperty("REST_CLIENT_READ_TIMEOUT_MS", Integer.class, 5000);
        if (connect <= 0 || read <= 0) { throw new IllegalStateException("Invalid Mail client timeout"); }
        http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(connect)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(read);
        client = RestClient.builder().baseUrl(base.toString()).requestFactory(factory).build();
    }

    public void afterCommit(Message message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) { throw new IllegalStateException("Mail requires committed state"); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { executor.execute(() -> deliver(message)); }
                catch (java.util.concurrent.RejectedExecutionException _) { failed(message.messageId()); }
            }
        });
    }

    private void deliver(Message message) {
        try {
            if (!clock.instant().isBefore(message.sendBefore())) { failed(message.messageId()); return; }
            client.post().uri("/internal/api/v1/mail/password-reset-jobs")
                    .headers(headers -> headers.setBearerAuth(serviceToken(message.messageId())))
                    .body(message).retrieve().toBodilessEntity();
        } catch (RuntimeException _) { failed(message.messageId()); }
    }

    public String serviceToken(UUID messageId) {
        Instant now = clock.instant();
        return Jwts.builder().issuer(settings.issuer()).subject(contract("subject"))
                .audience().add(contract("audience")).and().id(messageId.toString())
                .issuedAt(Date.from(now)).notBefore(Date.from(now))
                .expiration(Date.from(now.plusSeconds(Long.parseLong(contract("ttl-seconds")))))
                .claim("token_type", contract("token-type")).claim("scope", contract("scope"))
                .signWith(settings.privateKey(), Jwts.SIG.RS256).compact();
    }

    private String contract(String key) { return environment.getRequiredProperty("restofy.mail-contract." + key); }
    private static void failed(UUID id) { LOG.warn("mail_event=DISPATCH_FAILED message_id={}", id); }

    @PreDestroy
    public void close() { executor.shutdownNow(); http.close(); }

    public record Message(UUID messageId, String templateKey, String recipient, String locale, String code,
            Instant codeExpiresAt, Instant sendBefore) {
        @Override public String toString() { return "Auth mail command"; }
    }
}
