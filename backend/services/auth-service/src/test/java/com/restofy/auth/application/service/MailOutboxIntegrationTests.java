package com.restofy.auth.application.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.restofy.auth.infrastructure.client.MailClient.Message;
import com.restofy.auth.infrastructure.persistence.repository.MailOutboxRepository;
import com.restofy.auth.infrastructure.persistence.repository.MailOutboxRepository.Delivery;
import com.restofy.auth.support.AuthSessionTestSupport;
import com.restofy.auth.support.AuthTestDatabaseInitializer;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"auth.bootstrap.dev-users.enabled=false", "auth.bootstrap.initial-admin.enabled=false",
        "auth.mail-outbox.relay-enabled=false"})
@ContextConfiguration(initializers = AuthTestDatabaseInitializer.class)
@Import(MailOutboxIntegrationTests.TimeConfiguration.class)
class MailOutboxIntegrationTests extends AuthSessionTestSupport {
    @Autowired private MailOutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MutableAuthClock clock;

    @BeforeEach
    void setup() {
        clean();
        clock.reset();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM auth_mail_outbox");
    }

    @Test
    void requiresAnActiveTransactionAndRollsBackWithTheBusinessOperation() {
        Message message = message();
        assertThatThrownBy(() -> outbox.enqueue(message)).isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outbox.enqueue(message);
            status.setRollbackOnly();
        });
        assertThat(count()).isZero();
    }

    @Test
    void payloadIsEncryptedAtRestAndSameMessageIdIsIdempotentEvenAtCapacity() {
        Message message = message();
        enqueue(message);
        String ciphertext = ciphertext(message.messageId());
        assertThat(ciphertext).doesNotContain(message.recipient(), message.code(), "recipient", "templateKey");
        jdbc.update("""
                INSERT INTO auth_mail_outbox(message_id, status, encrypted_payload, send_before, next_attempt_at)
                SELECT md5(i::text)::uuid, 'PENDING', 'test-envelope', ?, ? FROM generate_series(1, ?) i
                """, Timestamp.from(clock.instant().plusSeconds(600)), Timestamp.from(clock.instant()),
                MailOutboxRepository.MAX_OPEN_MESSAGES - 1);
        enqueue(message);
        assertThat(count()).isEqualTo(MailOutboxRepository.MAX_OPEN_MESSAGES);
        assertThat(ciphertext(message.messageId())).isEqualTo(ciphertext);
        assertThatThrownBy(() -> enqueue(message())).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(count()).isEqualTo(MailOutboxRepository.MAX_OPEN_MESSAGES);
    }

    @Test
    void claimsRoundTripAndOnlyCurrentClaimCanCompleteOrRetry() {
        Message message = message();
        enqueue(message);
        Delivery delivery = outbox.claim().orElseThrow();
        assertThat(delivery.message()).isEqualTo(message);
        assertThat(delivery.attempts()).isEqualTo(1);
        assertThat(delivery.toString()).doesNotContain(message.recipient(), message.code());
        Delivery wrong = new Delivery(message, UUID.randomUUID(), delivery.attempts());
        outbox.delivered(wrong);
        outbox.retry(wrong);
        assertThat(status(message.messageId())).isEqualTo("SENDING");
        assertThat(outbox.claim()).isEmpty();
        outbox.delivered(delivery);
        assertTerminal(message.messageId(), "DELIVERED");
        outbox.retry(delivery);
        assertTerminal(message.messageId(), "DELIVERED");
    }

    @Test
    void expiredLeaseCanBeRecoveredWithoutAcceptingTheOldCompletion() {
        Message message = message();
        enqueue(message);
        Delivery old = outbox.claim().orElseThrow();
        jdbc.update("UPDATE auth_mail_outbox SET lease_until = ? WHERE message_id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), message.messageId());
        Delivery recovered = outbox.claim().orElseThrow();
        assertThat(recovered.claimId()).isNotEqualTo(old.claimId());
        assertThat(recovered.attempts()).isEqualTo(2);
        outbox.delivered(old);
        assertThat(status(message.messageId())).isEqualTo("SENDING");
        outbox.delivered(recovered);
        assertTerminal(message.messageId(), "DELIVERED");
    }

    @Test
    void retriesWaitForBackoffAndStopAfterEightAttempts() {
        Message message = message();
        enqueue(message);
        outbox.retry(outbox.claim().orElseThrow());
        assertThat(status(message.messageId())).isEqualTo("PENDING");
        assertThat(outbox.claim()).isEmpty();
        clock.advance(2);
        assertThat(outbox.claim().orElseThrow().attempts()).isEqualTo(2);
        jdbc.update("UPDATE auth_mail_outbox SET attempts = ?, lease_until = ? WHERE message_id = ?",
                MailOutboxRepository.MAX_ATTEMPTS - 1, Timestamp.from(clock.instant().minusSeconds(1)), message.messageId());
        Delivery last = outbox.claim().orElseThrow();
        assertThat(last.attempts()).isEqualTo(MailOutboxRepository.MAX_ATTEMPTS);
        outbox.retry(last);
        assertTerminal(message.messageId(), "FAILED");
    }

    @Test
    void abandonedLastAttemptIsFailedAndExpiredPayloadIsErased() {
        Message message = message();
        enqueue(message);
        outbox.claim().orElseThrow();
        jdbc.update("UPDATE auth_mail_outbox SET attempts = ?, lease_until = ? WHERE message_id = ?",
                MailOutboxRepository.MAX_ATTEMPTS, Timestamp.from(clock.instant().minusSeconds(1)), message.messageId());
        assertThat(outbox.claim()).isEmpty();
        assertTerminal(message.messageId(), "FAILED");
        Message expiring = message();
        enqueue(expiring);
        clock.advance(600);
        assertThat(outbox.claim()).isEmpty();
        assertTerminal(expiring.messageId(), "EXPIRED");
        assertThatThrownBy(() -> enqueue(expiring)).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void tamperedCiphertextIsFailedAndErasedWithoutEscapingItsContent() {
        Message message = message();
        enqueue(message);
        jdbc.update("UPDATE auth_mail_outbox SET encrypted_payload = ? WHERE message_id = ?",
                "private-invalid-encrypted-payload", message.messageId());
        assertThat(outbox.claim()).isEmpty();
        assertTerminal(message.messageId(), "FAILED");
    }

    @Test
    void retryIsCappedAtDeadlineAndExpiredSendingPayloadIsErased() {
        Message message = message();
        enqueue(message);
        Delivery delivery = outbox.claim().orElseThrow();
        clock.advance(599);
        outbox.retry(delivery);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM auth_mail_outbox WHERE message_id = ?",
                Timestamp.class, message.messageId()).toInstant()).isEqualTo(message.sendBefore());
        clock.advance(1);
        assertThat(outbox.claim()).isEmpty();
        assertTerminal(message.messageId(), "EXPIRED");

        Message sending = message();
        enqueue(sending);
        Delivery expired = outbox.claim().orElseThrow();
        clock.advance(600);
        outbox.retry(expired);
        assertTerminal(sending.messageId(), "EXPIRED");
    }

    @Test
    void concurrentWorkersClaimDifferentMessages() throws Exception {
        enqueue(message());
        enqueue(message());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return outbox.claim().orElseThrow();
            });
            var second = executor.submit(() -> {
                start.await();
                return outbox.claim().orElseThrow();
            });
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).message().messageId())
                    .isNotEqualTo(second.get(30, TimeUnit.SECONDS).message().messageId());
        }
    }

    private Message message() {
        Instant now = clock.instant();
        return new Message(UUID.randomUUID(), "ADMIN_PASSWORD_RESET", "private@example.test", "tr", "731954",
                now.plusSeconds(900), now.plusSeconds(600));
    }

    private void enqueue(Message message) {
        new TransactionTemplate(transactionManager).executeWithoutResult(_ -> outbox.enqueue(message));
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM auth_mail_outbox", Integer.class);
    }

    private String ciphertext(UUID id) {
        return jdbc.queryForObject("SELECT encrypted_payload FROM auth_mail_outbox WHERE message_id = ?", String.class, id);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM auth_mail_outbox WHERE message_id = ?", String.class, id);
    }

    private void assertTerminal(UUID id, String expected) {
        assertThat(status(id)).isEqualTo(expected);
        assertThat(ciphertext(id)).isNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableAuthClock outboxClock() {
            return new MutableAuthClock();
        }
    }
}
