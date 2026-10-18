package com.restofy.qr.application.service;

import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.infrastructure.persistence.repository.*;
import com.restofy.qr.infrastructure.security.GuestTokenCodec;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestSessionValidationService {
    private final GuestSessionRepository guests;
    private final TableSessionRepository sessions;
    private final DiningTableRepository tables;
    private final GuestTokenCodec tokens;
    private final Clock clock;

    public GuestSessionValidationService(GuestSessionRepository guests, TableSessionRepository sessions,
                                         DiningTableRepository tables, GuestTokenCodec tokens, Clock clock) {
        this.guests = guests;
        this.sessions = sessions;
        this.tables = tables;
        this.tokens = tokens;
        this.clock = clock;
    }

    // Internal context guard, not an HTTP auth scheme or an Order admission.
    // Caller must recheck in its mutation transaction; a returned snapshot is not a permit.
    @Transactional
    public ValidatedGuestContext validate(String token, UUID restaurantId, UUID tableSessionId, UUID guestSessionId) {
        String digest;
        try { digest = tokens.digest(token); }
        catch (IllegalArgumentException _) { throw new InvalidGuestSessionException(); }
        var location = guests.locateByDigest(digest).orElseThrow(InvalidGuestSessionException::new);
        if (!location.getRestaurantId().equals(restaurantId) || !location.getTableSessionId().equals(tableSessionId)
                || !location.getId().equals(guestSessionId)) {
            throw new InvalidGuestSessionException();
        }
        // Read immutable parent IDs only before the lock, fresh mutable state after it.
        UUID tableId = sessions.locateTableId(restaurantId, tableSessionId).orElseThrow(InvalidGuestSessionException::new);
        var table = tables.findForQrManagement(restaurantId, tableId).orElseThrow(InvalidGuestSessionException::new);
        var main = sessions.findByRestaurantIdAndId(restaurantId, tableSessionId).orElseThrow(InvalidGuestSessionException::new);
        var guest = guests.findByRestaurantIdAndTableSessionIdAndId(restaurantId, tableSessionId, guestSessionId)
                .orElseThrow(InvalidGuestSessionException::new);
        Instant now = clock.instant();
        if (!table.isActive() || table.getOperationalStatus() != TableOperationalStatus.OCCUPIED
                || !main.isOpenAt(now)
                || !guest.isUsableAt(now) || !guest.getTokenDigest().equals(digest)) {
            throw new InvalidGuestSessionException();
        }
        return new ValidatedGuestContext(restaurantId, tableId, tableSessionId, guestSessionId, now);
    }

    public record ValidatedGuestContext(UUID restaurantId, UUID tableId, UUID tableSessionId, UUID guestSessionId, Instant checkedAt) {
    }
}
