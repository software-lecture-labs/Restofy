package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.InternalTablePage;
import com.restofy.qr.api.dto.response.InternalTableResponse;
import com.restofy.qr.domain.entity.DiningTable;
import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.exception.TableNotFoundException;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.persistence.repository.QrScanEventRepository;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only table view for the Waiter service. Every query is scoped by the verified restaurant. */
@Service
@Profile("qr-order-inbound")
public class InternalTableQueryService {
    static final int MAX_SIZE = 200;
    private static final int MAX_PAGE = 10_000;
    private static final Set<TableSessionStatus> LIVE = Set.of(TableSessionStatus.OPEN, TableSessionStatus.CLOSING);

    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final QrScanEventRepository scans;

    public InternalTableQueryService(DiningTableRepository tables, TableSessionRepository sessions, QrScanEventRepository scans) {
        this.tables = tables;
        this.sessions = sessions;
        this.scans = scans;
    }

    @Transactional(readOnly = true)
    public InternalTablePage list(UUID restaurantId, int page, int size) {
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("Page must be 0.." + MAX_PAGE + " and size must be 1.." + MAX_SIZE + ".");
        }
        var result = tables.findTables(restaurantId, null, null, PageRequest.of(page, size, Sort.by("id")));
        var live = liveSessions(restaurantId, result.getContent().stream().map(DiningTable::getId).toList());
        var counts = scanCounts(restaurantId, live.values());
        var items = result.getContent().stream().map(table -> toResponse(table, live.get(table.getId()), counts)).toList();
        return new InternalTablePage(items, page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public InternalTableResponse get(UUID restaurantId, UUID tableId) {
        var table = tables.findByRestaurantIdAndId(restaurantId, tableId).orElseThrow(TableNotFoundException::new);
        var live = liveSessions(restaurantId, List.of(tableId));
        return toResponse(table, live.get(tableId), scanCounts(restaurantId, live.values()));
    }

    private Map<UUID, TableSession> liveSessions(UUID restaurantId, List<UUID> tableIds) {
        Map<UUID, TableSession> result = new HashMap<>();
        if (!tableIds.isEmpty()) {
            sessions.findByRestaurantIdAndTableIdInAndStatusIn(restaurantId, tableIds, LIVE)
                    .forEach(session -> result.put(session.getTableId(), session));
        }
        return result;
    }

    private Map<UUID, Long> scanCounts(UUID restaurantId, Collection<TableSession> live) {
        Map<UUID, Long> result = new HashMap<>();
        if (!live.isEmpty()) {
            scans.countScansBySession(restaurantId, live.stream().map(TableSession::getId).toList())
                    .forEach(row -> result.put(row.getTableSessionId(), row.getScans()));
        }
        return result;
    }

    private static InternalTableResponse toResponse(DiningTable table, TableSession session, Map<UUID, Long> counts) {
        var current = session == null ? null : new InternalTableResponse.CurrentSession(session.getId(),
                session.getStatus().name(), session.getOpenedAt(), session.getLastActivityAt(),
                counts.getOrDefault(session.getId(), 0L), session.getVersion());
        return new InternalTableResponse(table.getId(), table.getRestaurantId(), table.getTableNumber(),
                table.getDisplayName(), table.getCapacity(), table.getOperationalStatus().name(), table.isActive(),
                table.getVersion(), current);
    }
}
