package com.restofy.auth.application.service.revocation;

import java.util.UUID;

import com.restofy.auth.infrastructure.client.QrRevocationClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/** Transport orchestration only: all database work lives behind short transaction proxies. */
@Service
public class RevocationCoordinator {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RevocationCoordinator.class);
    private final RevocationTransactions transactions;
    private final QrRevocationClient qr;

    public RevocationCoordinator(RevocationTransactions transactions, QrRevocationClient qr) {
        this.transactions = transactions;
        this.qr = qr;
    }

    public UUID generation() {
        return qr.generation();
    }

    @Transactional(propagation = Propagation.NEVER)
    public void deliver(UUID id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("HTTP inside transaction");
        var operation = transactions.load(id);
        if (operation.status().equals("COMPLETED")) return;
        if (transactions.finalizeExpiredTargets(id)) return;
        String stage = "TRANSPORT";
        try {
            // Redelivery deliberately obtains a fresh signed receipt for the same immutable manifest.
            String receipt = qr.persist(operation);
            stage = "ACK_VALIDATION";
            transactions.recordAcknowledgement(id, receipt);
            stage = "FINALIZE";
            transactions.finalizeOperation(id);
        } catch (RuntimeException failure) {
            log.warn("Q5 operation pending operation={} stage={}", id, stage);
            // PREPARE/ACK survives. Never imply completion or undo a possibly committed QR fence.
            throw pending();
        }
    }

    private static ResponseStatusException pending() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Security transition pending; retry safely");
    }
}
