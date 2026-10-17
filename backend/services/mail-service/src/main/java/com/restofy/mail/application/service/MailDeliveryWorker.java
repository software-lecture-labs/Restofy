package com.restofy.mail.application.service;

import java.time.Clock;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.infrastructure.client.SmtpMailSender;
import com.restofy.mail.infrastructure.persistence.repository.MailJobRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class MailDeliveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(MailDeliveryWorker.class);
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2,
            Thread.ofPlatform().name("mail-delivery-", 0).factory());
    private final Semaphore capacity = new Semaphore(100);
    private final MailJobRepository jobs;
    private final SmtpMailSender smtp;
    private final Clock clock;
    public MailDeliveryWorker(MailJobRepository jobs, SmtpMailSender smtp, Clock clock) {
        this.jobs = jobs; this.smtp = smtp; this.clock = clock;
        executor.setRemoveOnCancelPolicy(true);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        executor.scheduleWithFixedDelay(() -> {
            try { jobs.expire(); } catch (RuntimeException _) { LOG.warn("mail_event=EXPIRY_FAILED"); }
        }, 0, 30, TimeUnit.SECONDS);
    }

    public void submit(PasswordResetMailRequest message) {
        if (!capacity.tryAcquire()) { jobs.rejected(message.messageId()); return; }
        try { executor.execute(() -> attempt(message)); }
        catch (java.util.concurrent.RejectedExecutionException _) { capacity.release(); jobs.rejected(message.messageId()); }
    }

    private void attempt(PasswordResetMailRequest message) {
        boolean retry = false;
        try {
            jobs.expire();
            int attempt = jobs.claim(message.messageId());
            if (attempt == 0) { return; }
            retry = deliver(message, attempt);
        } catch (RuntimeException _) {
            LOG.warn("mail_event=RESULT_UNKNOWN message_id={}", message.messageId());
        } finally { if (!retry) { capacity.release(); } }
    }

    private boolean deliver(PasswordResetMailRequest message, int attempt) {
        try { smtp.send(message); }
        catch (RuntimeException exception) { return failedDelivery(message, attempt, exception); }
        // A failed metadata commit after SMTP acceptance must never trigger another SMTP send.
        jobs.finish(message.messageId(), attempt, "SMTP_ACCEPTED", null);
        return false;
    }

    private boolean failedDelivery(PasswordResetMailRequest message, int attempt, RuntimeException exception) {
        Failure failure = classify(exception);
        boolean retry = failure == Failure.TRANSIENT && attempt < 3 && clock.instant().plusSeconds(5).isBefore(message.sendBefore());
        String terminalStatus = failure == Failure.UNKNOWN ? "UNKNOWN" : "FAILED";
        jobs.finish(message.messageId(), attempt, retry ? "RETRY" : terminalStatus, failure.name());
        LOG.warn("mail_event=DELIVERY_FAILED message_id={} result={}", message.messageId(), failure);
        if (retry) { executor.schedule(() -> attempt(message), 5, TimeUnit.SECONDS); }
        return retry;
    }

    public static Failure classify(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof org.springframework.mail.MailAuthenticationException
                    || current instanceof org.springframework.mail.MailPreparationException) { return Failure.PERMANENT; }
            if (current instanceof java.net.ConnectException || current instanceof java.net.UnknownHostException) { return Failure.TRANSIENT; }
            current = switch (current) {
                case org.springframework.mail.MailSendException send when !send.getFailedMessages().isEmpty() ->
                    send.getFailedMessages().values().iterator().next();
                case jakarta.mail.MessagingException messaging when messaging.getNextException() != null -> messaging.getNextException();
                default -> current.getCause();
            };
        }
        return Failure.UNKNOWN;
    }
    public enum Failure { TRANSIENT, PERMANENT, UNKNOWN }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
