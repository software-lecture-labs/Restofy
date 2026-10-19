package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.TableSessionResponse;
import com.restofy.qr.domain.entity.TableSession;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.exception.InvalidQrCodeException;
import com.restofy.qr.domain.exception.TableSessionConflictException;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TableSessionOpeningService {
    private final QrCodeValidationService validation;
    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;

    public TableSessionOpeningService(QrCodeValidationService validation, DiningTableRepository tables,
                                       TableSessionRepository sessions) {
        this.validation = validation;
        this.tables = tables;
        this.sessions = sessions;
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
            if (!session.isOpenAt(context.checkedAt())
                    || table.getOperationalStatus() != TableOperationalStatus.OCCUPIED) {
                throw new TableSessionConflictException();
            }
            // Repeat scans do not implement the unresolved idle-refresh policy.
            return new OpeningResult(metadata(session), false, context.qrId());
        }
        table.occupy();
        var session = sessions.saveAndFlush(new TableSession(context.restaurantId(), context.tableId(), context.checkedAt()));
        return new OpeningResult(metadata(session), true, context.qrId());
    }

    private static TableSessionResponse metadata(TableSession session) {
        return new TableSessionResponse(session.getId(), session.getRestaurantId(), session.getTableId(), session.getStatus(),
                session.getVersion(), session.getOpenedAt(), session.getLastActivityAt(), session.getIdleExpiresAt());
    }

    public record OpeningResult(TableSessionResponse session, boolean created, UUID qrCodeId) {
    }
}
