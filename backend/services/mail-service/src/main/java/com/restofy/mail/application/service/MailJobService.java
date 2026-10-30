package com.restofy.mail.application.service;

import com.restofy.mail.api.dto.request.PasswordResetMailRequest;
import com.restofy.mail.domain.entity.MailJob;
import com.restofy.mail.infrastructure.persistence.repository.MailJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class MailJobService {
    private final MailJobRepository jobs;
    private final MailDeliveryWorker worker;

    public MailJobService(MailJobRepository jobs, MailDeliveryWorker worker) {
        this.jobs = jobs;
        this.worker = worker;
    }

    @Transactional
    public MailJob accept(PasswordResetMailRequest message) {
        if (jobs.create(message)) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        worker.submit(message);
                    } catch (RuntimeException _) {
                        /* Accepted metadata remains available for deadline reconciliation. */
                    }
                }
            });
        }
        return jobs.find(message.messageId()).orElseThrow();
    }
}
