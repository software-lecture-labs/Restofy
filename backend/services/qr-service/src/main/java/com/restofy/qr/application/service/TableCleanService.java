package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.request.TableCleanRequest;
import com.restofy.qr.api.dto.response.InternalTableResponse;
import com.restofy.qr.api.dto.response.TableCleanResponse;
import com.restofy.qr.domain.entity.TableCleanCommand;
import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.enums.TableSessionStatus;
import com.restofy.qr.domain.exception.TableCleanException;
import com.restofy.qr.domain.exception.TableCleanException.Code;
import com.restofy.qr.domain.exception.TableNotFoundException;
import com.restofy.qr.infrastructure.persistence.repository.DiningTableRepository;
import com.restofy.qr.infrastructure.persistence.repository.TableCleanCommandRepository;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Table-based cleaning for the Waiter service. The table row is locked, so commands on one table are serialised and
 * the durable command record makes a replay of the same command ID idempotent. Rejections are recorded too, so the
 * status route can answer after a lost response. A rejection is returned (not thrown) so its record is committed.
 */
@Service
@Profile("qr-order-inbound")
public class TableCleanService {
    private static final Set<TableSessionStatus> LIVE = Set.of(TableSessionStatus.OPEN, TableSessionStatus.CLOSING);

    private final DiningTableRepository tables;
    private final TableSessionRepository sessions;
    private final TableCleanCommandRepository commands;
    private final InternalTableQueryService views;

    public TableCleanService(DiningTableRepository tables, TableSessionRepository sessions,
                             TableCleanCommandRepository commands, InternalTableQueryService views) {
        this.tables = tables;
        this.sessions = sessions;
        this.commands = commands;
        this.views = views;
    }

    @Transactional
    public TableCleanResponse clean(UUID restaurantId, TableCleanRequest request) {
        var table = tables.findForQrManagement(restaurantId, request.tableId())
                .orElseThrow(() -> new TableCleanException(Code.TABLE_NOT_FOUND));
        String digest = digest(request);
        var existing = commands.findByRestaurantIdAndCommandId(restaurantId, request.commandId());
        if (existing.isPresent()) {
            var stored = existing.get();
            if (!stored.getTableId().equals(request.tableId()) || !stored.getRequestDigest().equals(digest)) {
                throw new TableCleanException(Code.COMMAND_CONFLICT);
            }
            // A replayed rejection repeats the same definite rejection; only an applied command is a duplicate, and its
            // POST answer is the available table as the cleaning left it, not the table as it is now (the status GET shows that).
            if (stored.getStatus() != TableCleanCommand.Status.APPLIED) {
                return response(restaurantId, stored, "REJECTED");
            }
            var duplicate = response(restaurantId, stored, "DUPLICATE");
            return new TableCleanResponse(duplicate.commandId(), duplicate.status(), null, asCleaned(duplicate.table(), stored));
        }
        Code rejection = null;
        if (table.getVersion() != request.expectedVersion()) {
            rejection = Code.TABLE_VERSION_CONFLICT;
        } else if (!sessions.findByRestaurantIdAndTableIdInAndStatusIn(restaurantId, List.of(table.getId()), LIVE).isEmpty()) {
            rejection = Code.TABLE_SESSION_ACTIVE;
        } else if (!table.isActive() || table.getOperationalStatus() != TableOperationalStatus.CLEANING) {
            rejection = Code.TABLE_NOT_CLEANING;
        } else {
            table.finishCleaning();
            tables.saveAndFlush(table);
        }
        var outcome = rejection == null
                ? TableCleanCommand.applied(request.commandId(), restaurantId, table.getId(), digest,
                        request.requestedByUserId(), request.requestedAt(), table.getVersion())
                : TableCleanCommand.rejected(request.commandId(), restaurantId, table.getId(), digest,
                        request.requestedByUserId(), request.requestedAt(), rejection);
        var saved = commands.saveAndFlush(outcome);
        return response(restaurantId, saved, saved.getStatus().name());
    }

    @Transactional(readOnly = true)
    public TableCleanResponse status(UUID restaurantId, UUID tableId, UUID commandId) {
        var stored = commands.findByRestaurantIdAndCommandId(restaurantId, commandId)
                .filter(command -> command.getTableId().equals(tableId))
                .orElseThrow(() -> new TableCleanException(Code.COMMAND_NOT_FOUND));
        return response(restaurantId, stored, stored.getStatus().name());
    }

    private TableCleanResponse response(UUID restaurantId, TableCleanCommand command, String status) {
        try {
            var table = views.get(restaurantId, command.getTableId());
            String rejection = command.getRejectionCode() == null ? null : command.getRejectionCode().name();
            return new TableCleanResponse(command.getCommandId(), status, rejection, table);
        } catch (TableNotFoundException _) {
            throw new TableCleanException(Code.TABLE_NOT_FOUND);
        }
    }

    private static InternalTableResponse asCleaned(InternalTableResponse now, TableCleanCommand command) {
        return new InternalTableResponse(now.id(), now.restaurantId(), now.tableNumber(), now.displayName(), now.capacity(),
                TableOperationalStatus.AVAILABLE.name(), true, command.getAppliedTableVersion(), null);
    }

    private static String digest(TableCleanRequest request) {
        String canonical = String.join("|", request.restaurantId().toString(), request.tableId().toString(),
                request.expectedVersion().toString(), request.requestedByUserId().toString(), request.requestedAt().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
