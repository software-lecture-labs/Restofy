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
