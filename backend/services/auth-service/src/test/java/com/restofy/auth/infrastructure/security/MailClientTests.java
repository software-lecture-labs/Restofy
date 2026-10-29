package com.restofy.auth.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.restofy.auth.infrastructure.client.MailClient;
import com.restofy.auth.infrastructure.config.AuthSettings;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailClientTests {
    private MockEnvironment environment(String url) throws Exception {
        var env=new MockEnvironment().withProperty("auth.reset.mail-url",url).withProperty("REST_CLIENT_READ_TIMEOUT_MS","150");
        env.setActiveProfiles("test");
        var props=new Properties();
        try(var stream=new ClassPathResource("mail-service-contract.properties").getInputStream()) {props.load(stream);
        }
        props.forEach((key,value)->env.withProperty(key.toString(),value.toString()));
        return env;
    }
    @Test void signsDedicatedServiceClaimsAndSendsOnlyAfterCommitOverHttp() throws Exception {
        var keys=AuthSessionTestSupport.generateKeys();
        var settings=mock(AuthSettings.class);
        when(settings.privateKey()).thenReturn((java.security.interfaces.RSAPrivateCrtKey)keys.getPrivate());
        when(settings.issuer()).thenReturn("restofy-auth");
        UUID id=UUID.randomUUID();
        CountDownLatch received=new CountDownLatch(1);
        List<String> receivedTokens=new CopyOnWriteArrayList<>();
        List<String> bodies=new CopyOnWriteArrayList<>();
        HttpServer server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/api/v1/mail/password-reset-jobs",exchange->{
            receivedTokens.add(exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202,-1);
            exchange.close();
            received.countDown();
        });
        server.start();
        try(var client=new ClientResource(new MailClient(settings,environment("http://127.0.0.1:"+server.getAddress().getPort()),Clock
                .systemUTC()))) {
            var message=new MailClient.Message(id,"ADMIN_PASSWORD_RESET","admin@example.test","tr","123456",Instant.now().plusSeconds(
                    900),Instant.now().plusSeconds(100));
            assertThatThrownBy(()->client.value.afterCommit(message)).isInstanceOf(IllegalStateException.class);
            TransactionSynchronizationManager.initSynchronization();
            try {
                client.value.afterCommit(message);
                assertThat(receivedTokens).isEmpty();
                for(var callback:TransactionSynchronizationManager.getSynchronizations()) {callback.afterCommit();
                }
            } finally {TransactionSynchronizationManager.clearSynchronization();
            }
            assertThat(received.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(receivedTokens).hasSize(1);
            var claims=Jwts.parser().verifyWith((java.security.interfaces.RSAPublicKey)keys.getPublic()).requireAudience("restofy-mail")
                    .requireIssuer("restofy-auth").require("token_type","service").require("scope","password-reset:send")
                    .build().parseSignedClaims(receivedTokens.getFirst().substring(7)).getPayload();
            assertThat(claims.getSubject()).isEqualTo("auth-service");
            assertThat(claims.getId()).isEqualTo(id.toString());
            assertThat(claims.getExpiration().toInstant()).isEqualTo(claims.getIssuedAt().toInstant().plusSeconds(60));
            assertThat(bodies.getFirst()).contains("\"messageId\":\""+id,"\"code\":\"123456\"");
        } finally {server.stop(0);
        }
    }
    @Test void timeoutIsNotAutomaticallyRetriedAndExpiredDispatchNeverLeavesProcess() throws Exception {
        var keys=AuthSessionTestSupport.generateKeys();
        var settings=mock(AuthSettings.class);
        when(settings.privateKey()).thenReturn((java.security.interfaces.RSAPrivateCrtKey)keys.getPrivate());
        when(settings.issuer()).thenReturn("restofy-auth");
        AtomicInteger requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/api/v1/mail/password-reset-jobs",exchange->{requests.incrementAndGet();
            try {new CountDownLatch(1).await(300,TimeUnit.MILLISECONDS);
            }catch(InterruptedException _){Thread.currentThread()
                    .interrupt();
                    }exchange.close();
                    });
            server.start();
        try(var client=new ClientResource(new MailClient(settings,environment("http://127.0.0.1:"+server.getAddress().getPort()),Clock
                .systemUTC()))) {
            TransactionSynchronizationManager.initSynchronization();
            try {
                client.value.afterCommit(new MailClient.Message(UUID.randomUUID(),"PASSWORD_CHANGED","admin@example.test","tr",null,
                        null,Instant.now().plusSeconds(10)));
                client.value.afterCommit(new MailClient.Message(UUID.randomUUID(),"PASSWORD_CHANGED","admin@example.test","tr",null,
                        null,Instant.now().minusSeconds(1)));
                for(var callback:TransactionSynchronizationManager.getSynchronizations()) {callback.afterCommit();
                }
            }finally{TransactionSynchronizationManager.clearSynchronization();
            }
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(()->requests.get()==1);
            org.awaitility.Awaitility.await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(requests).hasValue(1));
        }finally{server.stop(0);
        }
    }
    private record ClientResource(MailClient value) implements AutoCloseable { @Override public void close(){value.close();
    } }
}
