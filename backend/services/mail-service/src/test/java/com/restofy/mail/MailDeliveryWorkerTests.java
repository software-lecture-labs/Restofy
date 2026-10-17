package com.restofy.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.application.service.MailDeliveryWorker;
import com.restofy.mail.infrastructure.client.SmtpMailSender;
import com.restofy.mail.infrastructure.persistence.repository.MailJobRepository;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

class MailDeliveryWorkerTests {
    private static int permits(MailDeliveryWorker worker) {
        return ((java.util.concurrent.Semaphore) org.springframework.test.util.ReflectionTestUtils.getField(worker,"capacity")).availablePermits();
    }
    private static PasswordResetMailRequest message(UUID id) {
        return new PasswordResetMailRequest(id,"PASSWORD_CHANGED","admin@example.test","en",null,null,Instant.now().plusSeconds(100));
    }

    @Test void capacitySlotIsReleasedAfterSuccessPermanentFailureAndSkippedClaim() {
        var jobs=mock(MailJobRepository.class); var smtp=mock(SmtpMailSender.class);
        UUID ok=UUID.randomUUID(), bad=UUID.randomUUID(), skipped=UUID.randomUUID();
        when(jobs.claim(ok)).thenReturn(1); when(jobs.claim(bad)).thenReturn(1); when(jobs.claim(skipped)).thenReturn(0);
        var badMessage=message(bad);
        doThrow(new org.springframework.mail.MailAuthenticationException("denied")).when(smtp).send(badMessage);
        var worker=new MailDeliveryWorker(jobs,smtp,Clock.systemUTC());
        try {
            worker.submit(message(ok)); worker.submit(badMessage); worker.submit(message(skipped));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(()->{
                verify(jobs).finish(ok,1,"SMTP_ACCEPTED",null);
                verify(jobs).finish(bad,1,"FAILED","PERMANENT");
                verify(jobs).claim(skipped);
                org.assertj.core.api.Assertions.assertThat(permits(worker)).isEqualTo(100);
            });
        } finally { worker.close(); }
    }

    @Test void retryKeepsExactlyOneSlotUntilTheFinalAttempt() {
        var jobs=mock(MailJobRepository.class); var smtp=mock(SmtpMailSender.class);
        UUID id=UUID.randomUUID();
        when(jobs.claim(id)).thenReturn(1).thenReturn(2);
        doThrow(new org.springframework.mail.MailSendException("down",new java.net.ConnectException("down"))).doNothing().when(smtp).send(any());
        var worker=new MailDeliveryWorker(jobs,smtp,Clock.systemUTC());
        try {
            worker.submit(message(id));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(()->verify(jobs).finish(id,1,"RETRY","TRANSIENT"));
            org.assertj.core.api.Assertions.assertThat(permits(worker)).isEqualTo(99);
            await().atMost(Duration.ofSeconds(9)).untilAsserted(()->{
                verify(jobs).finish(id,2,"SMTP_ACCEPTED",null);
                org.assertj.core.api.Assertions.assertThat(permits(worker)).isEqualTo(100);
            });
        } finally { worker.close(); }
    }

    @Test void metadataConnectionFailureAfterSmtpAcceptanceMustNotResend() {
        var jobs=mock(MailJobRepository.class); var smtp=mock(SmtpMailSender.class);
        UUID id=UUID.randomUUID();
        var message=new PasswordResetMailRequest(id,"PASSWORD_CHANGED","admin@example.test","en",null,null,Instant.now().plusSeconds(100));
        when(jobs.claim(id)).thenReturn(1);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test",new java.net.ConnectException("test")))
                .doNothing().when(jobs).finish(id,1,"SMTP_ACCEPTED",null);
        var worker=new MailDeliveryWorker(jobs,smtp,Clock.systemUTC());
        try {
            worker.submit(message);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(()->verify(jobs).finish(id,1,"SMTP_ACCEPTED",null));
            await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(8)).untilAsserted(()->verify(smtp,times(1)).send(message));
        }finally{worker.close();}
    }
}
