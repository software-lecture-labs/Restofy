package com.restofy.mail;

import java.net.ConnectException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.application.service.MailDeliveryWorker;
import com.restofy.mail.domain.enums.MailJobStatus;
import com.restofy.mail.infrastructure.client.SmtpMailSender;
import com.restofy.mail.infrastructure.persistence.repository.MailJobRepository;
import com.restofy.mail.infrastructure.persistence.repository.MailPayloadRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class MailDeliveryWorkerTests {
    private static int permits(MailDeliveryWorker worker) {
        return ((Semaphore) ReflectionTestUtils.getField(worker,"capacity")).availablePermits();
    }
    private static PasswordResetMailRequest message(UUID id) {
        return new PasswordResetMailRequest(id,"PASSWORD_CHANGED","admin@example.test","en",null,null,Instant.now().plusSeconds(100));
    }

    @Test void capacitySlotIsReleasedAfterSuccessPermanentFailureAndSkippedClaim() {
        var jobs=mock(MailJobRepository.class); var smtp=mock(SmtpMailSender.class);
        UUID ok=UUID.randomUUID(), bad=UUID.randomUUID(), skipped=UUID.randomUUID();
        when(jobs.claim(ok)).thenReturn(1); when(jobs.claim(bad)).thenReturn(1); when(jobs.claim(skipped)).thenReturn(0);
        var badMessage=message(bad);
        doThrow(new MailAuthenticationException("denied")).when(smtp).send(badMessage);
        var worker=new MailDeliveryWorker(jobs,smtp,Clock.systemUTC(),mock(MailPayloadRepository.class));
        try {
            worker.submit(message(ok)); worker.submit(badMessage); worker.submit(message(skipped));
            await().atMost(Duration.ofSeconds(3)).untilAsserted(()->{
                verify(jobs).finish(ok,1,MailJobStatus.SMTP_ACCEPTED,null);
                verify(jobs).finish(bad,1,MailJobStatus.FAILED,"PERMANENT");
                verify(jobs).claim(skipped);
                Assertions.assertThat(permits(worker)).isEqualTo(100);
            });
        } finally { worker.close(); }
    }

    @Test void retryKeepsExactlyOneSlotUntilTheFinalAttempt() {
        var jobs=mock(MailJobRepository.class); var smtp=mock(SmtpMailSender.class);
        UUID id=UUID.randomUUID();
        when(jobs.claim(id)).thenReturn(1).thenReturn(2);
        doThrow(new MailSendException("down",new ConnectException("down"))).doNothing().when(smtp).send(any());
        var worker=new MailDeliveryWorker(jobs,smtp,Clock.systemUTC(),mock(MailPayloadRepository.class));
        try {
            worker.submit(message(id));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(()->verify(jobs).finish(id,1,MailJobStatus.RETRY,"TRANSIENT"));
            Assertions.assertThat(permits(worker)).isEqualTo(99);
            await().atMost(Duration.ofSeconds(9)).untilAsserted(()->{
                verify(jobs).finish(id,2,MailJobStatus.SMTP_ACCEPTED,null);
                Assertions.assertThat(permits(worker)).isEqualTo(100);
            });
        } finally { worker.close(); }
    }

    @Test void metadataConnectionFailureAfterSmtpAcceptanceMustNotResend() {
        var jobs=mock(MailJobRepository.class); var smtp=mock(SmtpMailSender.class);
        UUID id=UUID.randomUUID();
        var message=new PasswordResetMailRequest(id,"PASSWORD_CHANGED","admin@example.test","en",null,null,Instant.now().plusSeconds(100));
        when(jobs.claim(id)).thenReturn(1);
        doThrow(new DataAccessResourceFailureException("test",new ConnectException("test")))
                .doNothing().when(jobs).finish(id,1,MailJobStatus.SMTP_ACCEPTED,null);
        var worker=new MailDeliveryWorker(jobs,smtp,Clock.systemUTC(),mock(MailPayloadRepository.class));
        try {
            worker.submit(message);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(()->verify(jobs).finish(id,1,MailJobStatus.SMTP_ACCEPTED,null));
            await().during(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(8)).untilAsserted(()->verify(smtp,times(1)).send(message));
        }finally{worker.close();}
    }
}
