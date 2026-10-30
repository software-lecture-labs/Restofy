package com.restofy.mail;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.application.service.MailDeliveryWorker;
import com.restofy.mail.application.service.MailJobService;
import com.restofy.mail.domain.enums.MailJobStatus;
import com.restofy.mail.infrastructure.client.SmtpMailSender;
import com.restofy.mail.infrastructure.persistence.repository.MailJobRepository;
import com.restofy.mail.infrastructure.persistence.repository.MailPayloadRepository;
import com.restofy.mail.support.MailTestDatabaseInitializer;
import com.restofy.mail.support.MailTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(initializers = MailTestDatabaseInitializer.class)
class MailPayloadIntegrationTests extends MailTestSupport {
    @Autowired private MailJobService service;
    @Autowired private MailJobRepository jobs;
    @Autowired private MailPayloadRepository payloads;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoBean private MailDeliveryWorker backgroundWorker;

    @BeforeEach
    void setup() {
        clean();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM mail_delivery_attempts");
        jdbc.update("DELETE FROM mail_delivery_payloads");
        jdbc.update("DELETE FROM mail_jobs");
    }

    @Test
    void acceptPersistsEncryptedContentAndDuplicateBodyNeverOverwritesTheFirstPayload() {
        PasswordResetMailRequest first = message(UUID.randomUUID());
        service.accept(first);
        String encrypted = ciphertext(first.messageId());
        assertThat(encrypted).doesNotContain(first.recipient(), first.code(), "recipient", "templateKey");
        PasswordResetMailRequest duplicate = new PasswordResetMailRequest(first.messageId(), first.templateKey(),
                "different@example.test", "en", "654321", first.codeExpiresAt(), first.sendBefore());
        service.accept(duplicate);
        assertThat(ciphertext(first.messageId())).isEqualTo(encrypted);
        assertThat(payloads.ready()).containsExactly(first);
        verify(backgroundWorker).submit(first);
        verify(backgroundWorker, never()).submit(duplicate);
    }

    @Test
    void payloadAndMetadataRollbackTogetherAndSaveRequiresAnActiveTransaction() {
        PasswordResetMailRequest message = message(UUID.randomUUID());
        assertThatThrownBy(() -> payloads.save(message)).isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            service.accept(message);
            transaction.setRollbackOnly();
        });
        assertThat(jobs.find(message.messageId())).isEmpty();
        assertThat(payloadCount()).isZero();
        verify(backgroundWorker, never()).submit(message);
    }

    @Test
    void aNewWorkerRecoversPersistedPendingContentAndRemovesItAfterSmtpAcceptance() {
        PasswordResetMailRequest message = message(UUID.randomUUID());
        service.accept(message);
        SmtpMailSender smtp = mock(SmtpMailSender.class);
        MailDeliveryWorker restarted = new MailDeliveryWorker(jobs, smtp, clock, payloads);
        try {
            restarted.ready();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                verify(smtp).send(message);
                assertThat(jobs.find(message.messageId()).orElseThrow().status()).isEqualTo(MailJobStatus.SMTP_ACCEPTED);
                assertThat(payloadCount()).isZero();
            });
        } finally {
            restarted.close();
        }
    }

    @Test
    void sendingAndNotYetDueRetryJobsAreNeverReturnedForBlindResend() {
        PasswordResetMailRequest sending = message(UUID.randomUUID());
        service.accept(sending);
        assertThat(jobs.claim(sending.messageId())).isEqualTo(1);
        PasswordResetMailRequest retry = message(UUID.randomUUID());
        service.accept(retry);
        assertThat(jobs.claim(retry.messageId())).isEqualTo(1);
        jobs.finish(retry.messageId(), 1, MailJobStatus.RETRY, "TRANSIENT");
        assertThat(payloads.ready()).isEmpty();
        assertThat(jobs.claim(retry.messageId())).isZero();
        jdbc.update("UPDATE mail_jobs SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minusSeconds(1)), retry.messageId());
        assertThat(payloads.ready()).containsExactly(retry);
        assertThat(payloadCount()).isEqualTo(2);
    }

    @Test
    void cleanupErasesTerminalAndExpiredPayloadsWhileKeepingLivePendingContent() {
        PasswordResetMailRequest terminal = message(UUID.randomUUID());
        PasswordResetMailRequest expired = message(UUID.randomUUID());
        PasswordResetMailRequest live = message(UUID.randomUUID());
        service.accept(terminal);
        service.accept(expired);
        service.accept(live);
        jdbc.update("UPDATE mail_jobs SET status = 'FAILED' WHERE id = ?", terminal.messageId());
        Instant now = clock.instant();
        jdbc.update("UPDATE mail_jobs SET created_at = ?, send_before = ? WHERE id = ?",
                Timestamp.from(now.minusSeconds(61)), Timestamp.from(now.minusSeconds(1)), expired.messageId());
        payloads.cleanup();
        assertThat(payloadCount()).isEqualTo(1);
        assertThat(payloads.ready()).containsExactly(live);
    }

    @Test
    void corruptCiphertextIsFailedWithSafeMetadataAndDeleted() {
        PasswordResetMailRequest message = message(UUID.randomUUID());
        service.accept(message);
        jdbc.update("UPDATE mail_delivery_payloads SET encrypted_payload = ? WHERE mail_job_id = ?",
                "private-invalid-ciphertext", message.messageId());
        assertThat(payloads.ready()).isEmpty();
        assertThat(jobs.find(message.messageId()).orElseThrow().status()).isEqualTo(MailJobStatus.PAYLOAD_LOST);
        assertThat(jdbc.queryForObject("SELECT error_code FROM mail_jobs WHERE id = ?", String.class, message.messageId()))
                .isEqualTo("PAYLOAD_INVALID");
        assertThat(payloadCount()).isZero();
    }

    private PasswordResetMailRequest message(UUID id) {
        Instant now = clock.instant();
        return new PasswordResetMailRequest(id, "ADMIN_PASSWORD_RESET", "private@example.test", "tr", "731954",
                now.plusSeconds(600), now.plusSeconds(100));
    }

    private String ciphertext(UUID id) {
        return jdbc.queryForObject("SELECT encrypted_payload FROM mail_delivery_payloads WHERE mail_job_id = ?", String.class, id);
    }

    private int payloadCount() {
        return jdbc.queryForObject("SELECT count(*) FROM mail_delivery_payloads", Integer.class);
    }
}
