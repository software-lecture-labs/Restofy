package com.restofy.auth.infrastructure.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.infrastructure.config.EnvironmentPolicy;
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

/**
 * Hands password-reset mail commands to the Mail service after the surrounding transaction has committed.
 *
 * <p>Delivery is best effort: the commands wait in a bounded in-memory queue and are not retried or persisted, so a
 * restart or a Mail outage drops them. The user can simply request a new code; this trade-off and the outbox
 * alternative are recorded in ADR-AUTH-001.
 */
@Component
@PropertySource("classpath:mail-service-contract.properties")
public class MailClient {
    private static final Logger LOG = LoggerFactory.getLogger(MailClient.class);
    private static final int DELIVERY_THREADS = 2;
    private static final int DELIVERY_QUEUE_CAPACITY = 100;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5_000;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(DELIVERY_THREADS, DELIVERY_THREADS, 0,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(DELIVERY_QUEUE_CAPACITY),
            Thread.ofPlatform().name("auth-mail-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final AuthSettings settings;
    private final Environment environment;
    private final Clock clock;
    private final HttpClient http;
    private final RestClient client;

    public MailClient(AuthSettings settings, Environment environment, Clock clock) {
        this.settings = settings;
        this.environment = environment;
        this.clock = clock;
        URI base = URI.create(environment.getRequiredProperty("auth.reset.mail-url"));
        boolean development = EnvironmentPolicy.isDevelopment(environment);
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !Set.of("http", "https").contains(base.getScheme())
                || (!development && !"https".equals(base.getScheme()))) {
            throw new IllegalStateException("Invalid Mail service address");
        }
        int connect = environment.getProperty("REST_CLIENT_CONNECT_TIMEOUT_MS", Integer.class,
                DEFAULT_CONNECT_TIMEOUT_MS);
        int read = environment.getProperty("REST_CLIENT_READ_TIMEOUT_MS", Integer.class, DEFAULT_READ_TIMEOUT_MS);
        if (connect <= 0 || read <= 0) {
            throw new IllegalStateException("Invalid Mail client timeout");
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connect)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(read);
        client = RestClient.builder().baseUrl(base.toString()).requestFactory(factory).build();
    }

    public void afterCommit(Message message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Mail requires committed state");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    executor.execute(() -> deliver(message));
                } catch (RejectedExecutionException _) {
                    failed(message.messageId());
                }
            }
        });
    }

    private void deliver(Message message) {
        try {
            if (!clock.instant().isBefore(message.sendBefore())) {
                failed(message.messageId());
                return;
            }
            client.post().uri(contract("reset-jobs-path"))
                    .headers(headers -> headers.setBearerAuth(serviceToken(message.messageId())))
                    .body(message).retrieve().toBodilessEntity();
        } catch (RuntimeException _) {
            failed(message.messageId());
        }
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

    private String contract(String key) {
        return environment.getRequiredProperty("restofy.mail-contract." + key);
    }

    private static void failed(UUID id) {
        LOG.warn("mail_event=DISPATCH_FAILED message_id={}", id);
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        http.close();
    }

    public record Message(UUID messageId, String templateKey, String recipient, String locale, String code,
            Instant codeExpiresAt, Instant sendBefore) {
        @Override
        public String toString() {
            return "Auth mail command";
        }
    }
}
