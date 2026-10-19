package com.restofy.qr.application.service;

import com.restofy.qr.domain.SessionClosure.Result;
import com.restofy.qr.infrastructure.security.SessionClosureEvidenceAccess;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TableSessionClosureService {
    private final SessionClosureTransactions transactions;
    private final SessionClosureEvidenceAccess evidence;
    public TableSessionClosureService(SessionClosureTransactions transactions, SessionClosureEvidenceAccess evidence) {
        this.transactions = transactions; this.evidence = evidence;
    }

    // Never hold a caller's DB lock/transaction across future source verification.
    @Transactional(propagation = Propagation.NEVER)
    public Result close(UUID sessionId, long expectedVersion) {
        var prepared = transactions.prepare(sessionId, expectedVersion);
        if (prepared.alreadyClosed() != null) { return prepared.alreadyClosed(); }
        var verified = evidence.verify(prepared.staff(), prepared.snapshot());
        return transactions.finish(prepared, verified);
    }

    @Transactional(propagation = Propagation.NEVER)
    public Result cleaned(UUID sessionId, long expectedTableVersion) {
        return transactions.cleaned(sessionId, expectedTableVersion);
    }
}
