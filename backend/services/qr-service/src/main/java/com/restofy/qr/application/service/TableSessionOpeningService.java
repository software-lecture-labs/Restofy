package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.TableSessionResponse;
import com.restofy.qr.domain.IdleWindow;
import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.exception.InvalidQrCodeException;
import com.restofy.qr.domain.exception.TableSessionConflictException;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionRepository;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TableSessionOpeningService {
    private final QrCodeValidationService validation;
    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final SessionOrderProtection protection;

    public TableSessionOpeningService(QrCodeValidationService validation, DiningTableRepository tables,
                                       TableSessionRepository sessions, SessionOrderProtection protection) {
        this.validation = validation;
        this.tables = tables;
        this.sessions = sessions;
        this.protection = protection;
    }

    @Transactional
    public OpeningResult open(String token) {
        // Validation joins this transaction: its table lock remains held through
        // lookup/insert and occupancy update, not just through a prior HTTP check.
        var context = validation.validate(token);
        var table = tables.findForQrManagement(context.restaurantId(), context.tableId())
                .orElseThrow(InvalidQrCodeException::new);
        var live = sessions.findByRestaurantIdAndTableIdAndStatusIn(context.restaurantId(), context.tableId(),
                List.of(TableSessionStatus.OPEN, TableSessionStatus.CLOSING));
        if (live.isPresent()) {
            var session = live.orElseThrow();
            boolean held = protection.snapshot(context.restaurantId(), session.getId(), context.checkedAt()).protectsMain();
            if (!session.isOpenAt(context.checkedAt(), held)
                    || table.getOperationalStatus() != TableOperationalStatus.OCCUPIED) {
                throw new TableSessionConflictException();
            }
            session.recordActivityAt(context.checkedAt(), held);
            sessions.flush();
            return new OpeningResult(metadata(session, held), false, context.qrId(), IdleWindow.storedTime(context.checkedAt()));
        }
        table.occupy();
        var session = sessions.saveAndFlush(new TableSession(context.restaurantId(), context.tableId(), context.checkedAt()));
        return new OpeningResult(metadata(session, false), true, context.qrId(), session.getOpenedAt());
    }

    private static TableSessionResponse metadata(TableSession session, boolean orderProtected) {
        return new TableSessionResponse(session.getId(), session.getRestaurantId(), session.getTableId(), session.getStatus(),
                session.getVersion(), session.getOpenedAt(), session.getLastActivityAt(), session.getIdleExpiresAt(), orderProtected);
    }

    public record OpeningResult(TableSessionResponse session, boolean created, UUID qrCodeId, Instant checkedAt) {
    }
}
