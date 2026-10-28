package com.restofy.mail.application.service;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.domain.enums.MailJobStatus;
import com.restofy.mail.domain.policy.MailPolicy;
import com.restofy.mail.infrastructure.client.SmtpMailSender;
import com.restofy.mail.infrastructure.persistence.repository.MailJobRepository;
import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;

@Component
public class MailDeliveryWorker {
    private static final Logger LOG = LoggerFactory.getLogger(MailDeliveryWorker.class);
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(MailPolicy.DELIVERY_THREADS,
            Thread.ofPlatform().name("mail-delivery-", 0).factory());
    private final Semaphore capacity = new Semaphore(MailPolicy.MAX_OPEN_JOBS);
    private final MailJobRepository jobs;
    private final SmtpMailSender smtp;
    private final Clock clock;

    public MailDeliveryWorker(MailJobRepository jobs, SmtpMailSender smtp, Clock clock) {
        this.jobs = jobs;
        this.smtp = smtp;
        this.clock = clock;
        executor.setRemoveOnCancelPolicy(true);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ready() {
        long period = MailPolicy.EXPIRY_SWEEP_PERIOD.toSeconds();
        executor.scheduleWithFixedDelay(() -> {
            try {
                jobs.expire();
            } catch (RuntimeException _) {
                LOG.warn("mail_event=EXPIRY_FAILED");
            }
        }, 0, period, TimeUnit.SECONDS);
    }

    public void submit(PasswordResetMailRequest message) {
        if (!capacity.tryAcquire()) {
            jobs.rejected(message.messageId());
            return;
        }
        try {
            executor.execute(() -> attempt(message));
        } catch (RejectedExecutionException _) {
            capacity.release();
            jobs.rejected(message.messageId());
        }
    }

    private void attempt(PasswordResetMailRequest message) {
        boolean retry = false;
        try {
            jobs.expire();
            int attempt = jobs.claim(message.messageId());
            if (attempt == 0) {
                return;
            }
            retry = deliver(message, attempt);
        } catch (RuntimeException _) {
            LOG.warn("mail_event=RESULT_UNKNOWN message_id={}", message.messageId());
        } finally {
            if (!retry) {
                capacity.release();
            }
        }
    }

    private boolean deliver(PasswordResetMailRequest message, int attempt) {
        try {
            smtp.send(message);
        } catch (RuntimeException exception) {
            return failedDelivery(message, attempt, exception);
        }
        // A failed metadata commit after SMTP acceptance must never trigger another SMTP send.
        jobs.finish(message.messageId(), attempt, MailJobStatus.SMTP_ACCEPTED, null);
        return false;
    }

    private boolean failedDelivery(PasswordResetMailRequest message, int attempt, RuntimeException exception) {
        Failure failure = classify(exception);
        boolean retry = failure == Failure.TRANSIENT && attempt < MailPolicy.MAX_ATTEMPTS
                && clock.instant().plus(MailPolicy.RETRY_DELAY).isBefore(message.sendBefore());
        MailJobStatus terminalStatus = failure == Failure.UNKNOWN ? MailJobStatus.UNKNOWN : MailJobStatus.FAILED;
        jobs.finish(message.messageId(), attempt, retry ? MailJobStatus.RETRY : terminalStatus, failure.name());
        LOG.warn("mail_event=DELIVERY_FAILED message_id={} result={}", message.messageId(), failure);
        if (retry) {
            executor.schedule(() -> attempt(message), MailPolicy.RETRY_DELAY.toSeconds(), TimeUnit.SECONDS);
        }
        return retry;
    }

    public static Failure classify(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MailPolicy.MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof MailAuthenticationException || current instanceof MailPreparationException) {
                return Failure.PERMANENT;
            }
            if (current instanceof ConnectException || current instanceof UnknownHostException) {
                return Failure.TRANSIENT;
            }
            current = switch (current) {
                case MailSendException send when !send.getFailedMessages().isEmpty() ->
                    send.getFailedMessages().values().iterator().next();
                case MessagingException messaging when messaging.getNextException() != null ->
                    messaging.getNextException();
                default -> current.getCause();
            };
        }
        return Failure.UNKNOWN;
    }

    public enum Failure { TRANSIENT, PERMANENT, UNKNOWN }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }
}
