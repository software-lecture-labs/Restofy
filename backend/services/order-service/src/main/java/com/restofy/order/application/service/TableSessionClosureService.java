package com.restofy.order.application.service;

import com.restofy.order.api.dto.request.ConfirmClosureRequest;
import com.restofy.order.api.dto.request.PrepareClosureRequest;
import com.restofy.order.api.dto.response.ClosureResponse;
import com.restofy.order.application.port.ServiceActor;
import com.restofy.order.domain.closure.ClosureBlocker;
import com.restofy.order.domain.closure.ClosureEligibility;
import com.restofy.order.domain.closure.ClosureOutcome;
import com.restofy.order.domain.enums.ClosureConfirmation;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.persistence.TableSessionClosureStore;
import com.restofy.order.infrastructure.persistence.TableSessionClosureStore.Closure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Closing a table session, from the Order Service's side (ADR-QR-001 K1, K2, K3, K7, K8, K9).
 *
 * <p>The QR Service owns the table and its sessions and does the closing; the Order Service owns the orders and decides
 * from ALL of them whether closing is allowed, so that the QR Service keeps no second copy of orders or money.
 * <ol>
 *   <li><b>Prepare</b>: under the table session's exclusive lock (which waits for every checkout in flight) the
 *       eligibility is computed. Only an ELIGIBLE closure is stored (PREPARED); from then on checkout refuses new orders
 *       for the session. Anything else changes nothing and orders continue.</li>
 *   <li><b>Confirm</b>: the QR Service reports CLOSED or NOT_CLOSED. CLOSED is only accepted while the orders still hash to
 *       the fingerprint the closure was prepared on, otherwise it is refused and the closure stays PREPARED (orders stay
 *       refused) until people reconcile it. NOT_CLOSED releases the hold. Nothing is ever released by time: a timeout
 *       does not prove the closing failed.</li>
 * </ol>
 * Every step is idempotent per operation id, so a lost response is answered by asking again. This does not make the QR
 * and Order databases atomic: the two services agree through these durable records and the confirmation.
 */
@Slf4j
@Service
public class TableSessionClosureService {

    private static final String NOT_PREPARED = "NOT_PREPARED";

    private final TableSessionClosureStore closures;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public TableSessionClosureService(TableSessionClosureStore closures, Clock clock,
                                      PlatformTransactionManager txManager) {
        this.closures = closures;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    /**
     * Called by checkout inside its transaction, as the first thing it does. Takes the shared ordering lock and refuses an
     * order for a table session whose closure is prepared or completed; nothing has been written yet when it throws.
     */
    public void requireOpenForCheckout(UUID restaurantId, UUID tableSessionId) {
        closures.lockForCheckout(restaurantId, tableSessionId);
        if (closures.findLive(restaurantId, tableSessionId).isPresent()) {
            throw new BusinessException(ErrorCode.TABLE_SESSION_CLOSING,
                    "The table is being closed; an order cannot be placed for this table session");
        }
    }

    public ClosureResponse prepare(ServiceActor service, UUID tableSessionId, PrepareClosureRequest request) {
        UUID restaurantId = request.restaurantId();
        return writeTx.execute(status -> {
            closures.lockForClosure(restaurantId, tableSessionId);
            Optional<Closure> known = closures.findByOperation(request.operationId());
            if (known.isPresent()) {
                return stored(requireSameScope(known.get(), restaurantId, tableSessionId));
            }
            closures.findLive(restaurantId, tableSessionId).ifPresent(live -> {
                throw new BusinessException(ErrorCode.CLOSURE_IN_PROGRESS,
                        "Another closure of this table session is already " + live.status().name().toLowerCase(),
                        Map.of("operationId", live.operationId(), "state", live.status()));
            });
            var result = ClosureEligibility.evaluate(closures.loadFacts(restaurantId, tableSessionId));
            if (!result.eligible()) {
                log.info("Table session closure not prepared: service={} restaurantId={} tableSessionId={} outcome={}"
                                + " blockers={}", service.name(), restaurantId, tableSessionId, result.outcome(),
                        result.blockers());
                return new ClosureResponse(request.operationId(), restaurantId, tableSessionId, NOT_PREPARED,
                        result.outcome(), sorted(result.blockers()), result.orderCount(), result.fingerprint(),
                        null, null, null);
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            closures.insertPrepared(request.operationId(), restaurantId, tableSessionId, result.orderCount(),
                    result.fingerprint(), now);
            log.info("Table session closure prepared: service={} operationId={} restaurantId={} tableSessionId={}"
                    + " orders={}", service.name(), request.operationId(), restaurantId, tableSessionId,
                    result.orderCount());
            return stored(closures.findByOperation(request.operationId()).orElseThrow());
        });
    }

    /** What became of an operation, so a QR Service that lost the response can ask again. */
    public ClosureResponse get(UUID restaurantId, UUID tableSessionId, UUID operationId) {
        return readTx.execute(status -> stored(closures.findByOperation(operationId)
                .filter(c -> c.restaurantId().equals(restaurantId) && c.tableSessionId().equals(tableSessionId))
                .orElseThrow(TableSessionClosureService::notFound)));
    }

    public ClosureResponse confirm(ServiceActor service, UUID tableSessionId, UUID operationId,
                                   ConfirmClosureRequest request) {
        UUID restaurantId = request.restaurantId();
        return writeTx.execute(status -> {
            closures.lockForClosure(restaurantId, tableSessionId);
            Closure closure = closures.findByOperation(operationId)
                    .filter(c -> c.restaurantId().equals(restaurantId) && c.tableSessionId().equals(tableSessionId))
                    .orElseThrow(TableSessionClosureService::notFound);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (request.outcome() == ClosureConfirmation.CLOSED) {
                confirmClosed(closure, now);
            } else {
                confirmNotClosed(closure, now);
            }
            log.info("Table session closure confirmed: service={} operationId={} restaurantId={} tableSessionId={}"
                    + " outcome={}", service.name(), operationId, restaurantId, tableSessionId, request.outcome());
            return stored(closures.findByOperation(operationId).orElseThrow());
        });
    }

    private void confirmClosed(Closure closure, Instant now) {
        switch (closure.status()) {
            case COMPLETED -> {
                // the same confirmation again: nothing to do
            }
            case RELEASED -> throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "This closure was released; it cannot be completed");
            case PREPARED -> {
                var current = ClosureEligibility.evaluate(
                        closures.loadFacts(closure.restaurantId(), closure.tableSessionId()));
                if (!current.eligible() || !current.fingerprint().equals(closure.scopeFingerprint())) {
                    throw new BusinessException(ErrorCode.CLOSURE_SCOPE_CHANGED,
                            "The orders of the table session changed after the closure was prepared; it is not completed",
                            Map.of("outcome", current.outcome(), "blockers", sorted(current.blockers())));
                }
                closures.markCompleted(closure.operationId(), now);
            }
        }
    }

    private void confirmNotClosed(Closure closure, Instant now) {
        switch (closure.status()) {
            case RELEASED -> {
                // the same confirmation again: nothing to do
            }
            case COMPLETED -> throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "This closure was completed; it cannot be released");
            case PREPARED -> closures.markReleased(closure.operationId(), now);
        }
    }

    /** A known operation id must always mean the same restaurant and table session; anything else is a conflict. */
    private static Closure requireSameScope(Closure closure, UUID restaurantId, UUID tableSessionId) {
        if (!closure.restaurantId().equals(restaurantId) || !closure.tableSessionId().equals(tableSessionId)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "This operation id was already used for another table session");
        }
        return closure;
    }

    private static ClosureResponse stored(Closure c) {
        // only an ELIGIBLE closure is ever stored, so that is what its stored form says
        return new ClosureResponse(c.operationId(), c.restaurantId(), c.tableSessionId(), c.status().name(),
                ClosureOutcome.ELIGIBLE, List.of(), c.orderCount(), c.scopeFingerprint(), c.preparedAt(),
                c.completedAt(), c.releasedAt());
    }

    private static List<ClosureBlocker> sorted(Collection<ClosureBlocker> blockers) {
        return blockers.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.CLOSURE_NOT_FOUND, "No such table session closure");
    }
}
