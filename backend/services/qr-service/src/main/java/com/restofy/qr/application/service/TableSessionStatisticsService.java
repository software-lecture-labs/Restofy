package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.TableSessionStatisticsResponse;
import com.restofy.qr.domain.enums.GuestSessionStatus;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.exception.TableNotFoundException;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.TableManagementAccess;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TableSessionStatisticsService {
    private final TableManagementAccess access;
    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final GuestSessionRepository guests;
    private final QrScanEventRepository scans;
    private final Clock clock;
    private final SessionOrderProtection protection;

    public TableSessionStatisticsService(TableManagementAccess access, DiningTableRepository tables, TableSessionRepository sessions,
                                          GuestSessionRepository guests, QrScanEventRepository scans, Clock clock, SessionOrderProtection protection) {
        this.access = access;
        this.tables = tables;
        this.sessions = sessions;
        this.guests = guests;
        this.scans = scans;
        this.clock = clock;
        this.protection = protection;
    }

    @Transactional
    public TableSessionStatisticsResponse get(UUID tableId) {
        UUID restaurantId = access.requireRestaurantId();
        // The scan writer uses this same parent lock, so no partial guest/event
        // transaction can be observed between the three aggregate queries.
        var table = tables.findForQrManagement(restaurantId, tableId).orElseThrow(TableNotFoundException::new);
        var now = clock.instant();
        var live = sessions.findByRestaurantIdAndTableIdAndStatusIn(restaurantId, tableId,
                List.of(TableSessionStatus.OPEN, TableSessionStatus.CLOSING));
        long tableScans = scans.countByRestaurantIdAndTableId(restaurantId, tableId);
        if (live.isEmpty()) {
            return new TableSessionStatisticsResponse(restaurantId, tableId, null, null, 0, 0, tableScans, now);
        }
        var main = live.orElseThrow();
        // Stored timestamptz values have microsecond precision. Floor the cutoff
        // so JDBC cannot round a just-before-expiry Instant up to the expiry itself.
        var held = protection.snapshot(restaurantId, main.getId(), now);
        long active = table.isActive() && table.getOperationalStatus() == TableOperationalStatus.OCCUPIED && main.isOpenAt(now, held.protectsMain())
                ? guests.countUsable(restaurantId, main.getId(), GuestSessionStatus.ACTIVE, now.truncatedTo(ChronoUnit.MICROS)) : 0;
        return new TableSessionStatisticsResponse(restaurantId, tableId, main.getId(), main.getStatus(), active,
                scans.countByRestaurantIdAndTableSessionId(restaurantId, main.getId()), tableScans, now);
    }
}
