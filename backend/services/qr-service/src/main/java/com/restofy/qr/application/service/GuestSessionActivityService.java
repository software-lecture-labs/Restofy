package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.GuestActivityResponse;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.infrastructure.persistence.repository.GuestSessionRepository;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class GuestSessionActivityService {
    private final GuestSessionValidationService validation;
    private final TableSessionRepository sessions;
    private final GuestSessionRepository guests;
    private final SessionOrderProtection protection;
    private final TransactionTemplate writes;

    public GuestSessionActivityService(GuestSessionValidationService validation, TableSessionRepository sessions,
                                      GuestSessionRepository guests, SessionOrderProtection protection,
                                      PlatformTransactionManager manager) {
        this.validation = validation; this.sessions = sessions; this.guests = guests; this.protection = protection;
        writes = new TransactionTemplate(manager);
    }

    @Transactional(propagation = Propagation.NEVER)
    public GuestActivityResponse recordActivity(String guestToken) {
        // The credential alone identifies the guest; restaurant/session/guest ids come from the server-side record.
        var prepared = validation.prepare(guestToken);
        return writes.execute(status -> {
            var context = validation.validateLocked(guestToken, prepared);
            var main = sessions.findByRestaurantIdAndId(context.restaurantId(), context.tableSessionId())
                    .orElseThrow(InvalidGuestSessionException::new);
            var guest = guests.findByRestaurantIdAndTableSessionIdAndId(context.restaurantId(), main.getId(), context.guestSessionId())
                    .orElseThrow(InvalidGuestSessionException::new);
            boolean mainHeld = protection.protectsMain(prepared.state(), main, context.checkedAt());
            boolean guestHeld = protection.protectsGuest(prepared.state(), main, guest.getId(), context.checkedAt());
            protection.cancellationDeadline(prepared.state(), main, guest, context.checkedAt());
            main.recordActivityAt(context.checkedAt(), mainHeld);
            guest.recordActivityAt(context.checkedAt(), guestHeld);
            sessions.flush();
            return new GuestActivityResponse(context.restaurantId(), main.getId(), guest.getId(), main.getVersion(), guest.getVersion(),
                    guest.getLastActivityAt(), main.getIdleExpiresAt(), guest.getExpiresAt(), mainHeld, guestHeld);
        });
    }
}
