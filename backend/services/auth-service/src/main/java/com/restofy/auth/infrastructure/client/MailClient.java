package com.restofy.auth.infrastructure.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import com.restofy.auth.infrastructure.config.AuthMailSettings;
import com.restofy.auth.infrastructure.config.EnvironmentPolicy;
import com.restofy.auth.infrastructure.persistence.repository.MailOutboxRepository;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Persists mail commands atomically with Auth state; the relay sends them only after commit.
 *
 * <p>The HTTP transport deliberately propagates failures. The durable relay owns bounded retries and preserves
 * message IDs so a lost acknowledgement never creates a different Mail job.
 */
@Component
@PropertySource("classpath:mail-service-contract.properties")
public class MailClient implements AutoCloseable {
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5_000;

    private final AuthMailSettings settings;
    private final MailOutboxRepository outbox;
    private final Environment environment;
    private final Clock clock;
    private final HttpClient http;
    private final RestClient client;

    public MailClient(AuthMailSettings settings, Environment environment, Clock clock, MailOutboxRepository outbox) {
        this.settings = settings;
        this.outbox = outbox;
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

    public void enqueue(Message message) {
        outbox.enqueue(message);
    }

    public void deliver(Message message) {
        if (!clock.instant().isBefore(message.sendBefore())) {
            throw new IllegalStateException("Mail command deadline exceeded");
        }
        client.post().uri(contract("reset-jobs-path"))
                .headers(headers -> headers.setBearerAuth(serviceToken(message.messageId())))
                .body(message).retrieve().toBodilessEntity();
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

    @PreDestroy
    @Override
    public void close() {
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
