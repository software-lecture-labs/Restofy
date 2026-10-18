package com.restofy.waiter.application.service;

import com.restofy.waiter.api.dto.response.ClosureEligibilityResponse;
import com.restofy.waiter.api.dto.response.PendingCommandResponse;
import com.restofy.waiter.domain.entity.TableCommand;
import com.restofy.waiter.domain.enums.ClosureBlocker;
import com.restofy.waiter.domain.enums.ServiceRequestStatus;
import com.restofy.waiter.domain.enums.TableCommandStatus;
import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.domain.exception.ResourceNotFoundException;
import com.restofy.waiter.infrastructure.client.order.OrderClient;
import com.restofy.waiter.infrastructure.client.order.OrderDto;
import com.restofy.waiter.infrastructure.client.order.OrderPageDto;
import com.restofy.waiter.infrastructure.client.qr.QrClient;
import com.restofy.waiter.infrastructure.client.qr.QrSessionDto;
import com.restofy.waiter.infrastructure.client.qr.QrTableDto;
import com.restofy.waiter.infrastructure.persistence.repository.ServiceRequestRepository;
import com.restofy.waiter.infrastructure.persistence.repository.TableCommandRepository;
import com.restofy.waiter.infrastructure.security.AuthenticatedStaff;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The informative closure view of one table: QR's live session, the orders of that session as Order reports
 * them (with Kitchen's readiness and the verified money facts) and the local count of open calls. Evaluation is
 * the pure {@link ClosureMatrix}. The answer is never a permission: QR decides when the close command arrives.
 *
 * <p>Orders are read page by page with a fixed upper bound; a session with more orders than Waiter assesses
 * gets the {@code TOO_MANY_ORDERS_TO_ASSESS} blocker instead of a partial verdict. A QR or Order outage is an
 * error, never an empty or a "closable" answer.
 */
@Service
public class ClosureEligibilityService {

    /** What this view cannot see; the close command is checked by the owners for exactly these as well. */
    static final List<String> NOT_ASSESSED = List.of("ORDER_ADMISSIONS_IN_FLIGHT", "PAYMENT_PROVIDER_REALTIME_STATE");
    private static final int PAGE_SIZE = 100;

    private final QrClient qr;
    private final OrderClient orders;
    private final ServiceRequestRepository calls;
    private final TableCommandRepository commands;
    private final Clock clock;

    public ClosureEligibilityService(QrClient qr, OrderClient orders, ServiceRequestRepository calls,
                                     TableCommandRepository commands, Clock clock) {
        this.qr = qr;
        this.orders = orders;
        this.calls = calls;
        this.commands = commands;
        this.clock = clock;
    }

    public ClosureEligibilityResponse assess(AuthenticatedStaff staff, UUID tableId) {
        UUID restaurantId = AuthenticatedStaff.requireWaiterRestaurant(staff);
        QrTableDto table = qr.findTable(restaurantId, tableId)
                .orElseThrow(() -> new ResourceNotFoundException("TABLE_NOT_FOUND", "Table not found"));
        QrSessionDto session = table.currentSession();
        PendingCommandResponse pending = commands.findByRestaurantIdAndStatusInOrderByCreatedAtDesc(restaurantId,
                        TableCommandStatus.OPEN).stream().filter(command -> command.getTableId().equals(tableId))
                .findFirst().map(ClosureEligibilityService::pending).orElse(null);
        List<ClosureMatrix.Finding> findings = new ArrayList<>(ClosureMatrix.forSession(session == null ? null : session.status()));
        int orderCount = 0;
        int openCalls = 0;
        if (session != null) {
            openCalls = (int) calls.countByRestaurantIdAndTableSessionIdAndStatusIn(restaurantId, session.id(),
                    ServiceRequestStatus.UNRESOLVED);
            List<OrderDto> sessionOrders = readOrders(restaurantId, tableId, session.id());
            if (sessionOrders == null) {
                findings.add(new ClosureMatrix.Finding(ClosureBlocker.TOO_MANY_ORDERS_TO_ASSESS, null, null));
            } else {
                orderCount = sessionOrders.size();
                findings.addAll(ClosureMatrix.forOrders(sessionOrders));
            }
        }
        List<ClosureEligibilityResponse.Blocker> blockers = findings.stream()
                .map(finding -> new ClosureEligibilityResponse.Blocker(finding.blocker(), finding.orderId(), finding.orderNumber()))
                .toList();
        return new ClosureEligibilityResponse(tableId, session == null ? null : session.id(),
                session == null ? null : session.status(), session == null ? null : session.version(), clock.instant(),
                true, blockers.isEmpty(), blockers, orderCount, openCalls, pending, NOT_ASSESSED);
    }

    /** Every order of the session, or null when there are more than the assessment supports. */
    private List<OrderDto> readOrders(UUID restaurantId, UUID tableId, UUID sessionId) {
        List<OrderDto> all = new ArrayList<>();
        for (int page = 0; page <= ClosureMatrix.MAX_ORDERS / PAGE_SIZE; page++) {
            OrderPageDto result = orders.search(restaurantId, tableId, List.of(sessionId), null, page, PAGE_SIZE);
            if (result.totalElements() > ClosureMatrix.MAX_ORDERS) return null;
            all.addAll(result.items());
            if (all.size() == result.totalElements()) return all;
            if (all.size() > result.totalElements() || result.items().isEmpty()) {
                throw new DependencyException(OrderClient.DEPENDENCY, DependencyFailure.BAD_RESPONSE,
                        "order service returned an unusable response: order count does not match the total");
            }
        }
        return null;
    }

    static PendingCommandResponse pending(TableCommand command) {
        return new PendingCommandResponse(command.getId(), command.getType(), command.getStatus(), command.getCreatedAt());
    }
}
