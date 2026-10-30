package com.restofy.mail.application.service;

import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.domain.entity.MailJob;
import com.restofy.mail.infrastructure.persistence.repository.MailJobRepository;
import com.restofy.mail.infrastructure.persistence.repository.MailPayloadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MailJobService {
    private final MailJobRepository jobs;
    private final MailDeliveryWorker worker;
    private final MailPayloadRepository payloads;

    public MailJobService(MailJobRepository jobs, MailDeliveryWorker worker, MailPayloadRepository payloads) {
        this.jobs = jobs;
        this.worker = worker;
        this.payloads = payloads;
    }

    @Transactional
    public MailJob accept(PasswordResetMailRequest message) {
        if (jobs.create(message)) {
            payloads.save(message);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        worker.submit(message);
                    } catch (RuntimeException _) {
                        /* Committed encrypted payload remains available for recovery. */
                    }
                }
            });
        }
        return jobs.find(message.messageId()).orElseThrow();
    }
}
