package com.restofy.qr.application.service;

import com.restofy.qr.application.port.OrderSessionAccess;
import com.restofy.qr.domain.SessionClosure.Result;
import com.restofy.qr.domain.SessionTransition.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Automatic QR side of the durable Order-gated protocol. No staff approval or Order/Payment ledger is stored. */
@Service
public class TableSessionClosureService {
    private final SessionTransitionTransactions transitions;
    private final SessionClosureTransactions cleaning;
    private final SessionOrderProtection protection;
    private final OrderSessionAccess orders;
    private final Clock clock;
    public TableSessionClosureService(SessionTransitionTransactions transitions, SessionClosureTransactions cleaning,
                                       SessionOrderProtection protection, OrderSessionAccess orders, Clock clock) {
        this.transitions = transitions; this.cleaning = cleaning; this.protection = protection; this.orders = orders; this.clock = clock;
    }

    @Transactional(propagation = Propagation.NEVER)
    public OperationView evaluate(UUID restaurantId, UUID sessionId, Purpose purpose) {
        var state = protection.inspect(restaurantId, sessionId);
        UUID operationId = transitions.begin(restaurantId, sessionId, purpose, state);
        return operationId == null ? null : resumeOperation(restaurantId, operationId);
    }

    @Transactional(propagation = Propagation.NEVER)
    public OperationView resume(UUID restaurantId, UUID operationId) {
        return resumeOperation(restaurantId, operationId);
    }

    private OperationView resumeOperation(UUID restaurantId, UUID operationId) {
        var attempt = transitions.attempt(restaurantId, operationId);
        var command = attempt.command();
        if (attempt.stage() == Stage.COMPLETED || attempt.stage() == Stage.ABORTED) { return transitions.view(restaurantId, operationId); }
        if (attempt.stage() == Stage.APPLIED) {
            if (orders.acknowledge(command, attempt.result())) { transitions.acknowledge(restaurantId, operationId); }
            return transitions.view(restaurantId, operationId);
        }
        if (attempt.stage() == Stage.ABORTING) { return abort(command); }
        var authority = attempt.previousAttempts() == 0 ? orders.prepare(command) : orders.status(command);
        if (!current(authority, command)) { return transitions.view(restaurantId, operationId); }
        if (authority.status() == SourceStatus.NOT_STARTED) { authority = orders.prepare(command); }
        if (!current(authority, command)) { return transitions.view(restaurantId, operationId); }
        if (authority.status() == SourceStatus.BLOCKED) {
            transitions.requireAbort(restaurantId, operationId); return abort(command);
        }
        if (!authority.permits(command, clock.instant())) { return transitions.view(restaurantId, operationId); }
        if (!transitions.apply(restaurantId, operationId, authority)) { return abort(command); }
        var view = transitions.view(restaurantId, operationId);
        if (orders.acknowledge(command, view.result())) { transitions.acknowledge(restaurantId, operationId); }
        return transitions.view(restaurantId, operationId);
    }

    private boolean current(Authority authority, Command command) {
        return authority != null && authority.isCurrent(command, clock.instant());
    }

    private OperationView abort(Command command) {
        var restaurantId = command.snapshot().restaurantId();
        if (orders.abort(command)) { transitions.abortConfirmed(restaurantId, command.operationId()); }
        return transitions.view(restaurantId, command.operationId());
    }

    @Transactional(propagation = Propagation.NEVER)
    public Result cleaned(UUID sessionId, long expectedTableVersion) { return cleaning.cleaned(sessionId, expectedTableVersion); }
}
