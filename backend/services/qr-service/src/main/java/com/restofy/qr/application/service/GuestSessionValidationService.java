package com.restofy.qr.application.service;

import com.restofy.qr.domain.enums.TableOperationalStatus;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestSessionValidationService {
    private final GuestSessionContextReader contexts;
    private final SessionOrderProtection protection;

    public GuestSessionValidationService(GuestSessionContextReader contexts, SessionOrderProtection protection) {
        this.contexts = contexts;
        this.protection = protection;
    }

    // Internal context guard, not an HTTP auth scheme or an Order admission.
    // Caller must recheck in its mutation transaction; a snapshot is not a permit.
    @Transactional
    public ValidatedGuestContext validate(String token, UUID restaurantId, UUID tableSessionId, UUID guestSessionId) {
        var locked = contexts.lockIdentity(token, restaurantId, tableSessionId, guestSessionId);
        var main = locked.parent().main();
        var table = locked.parent().table();
        var guest = locked.guest();
        var now = locked.parent().checkedAt();
        var held = protection.snapshot(restaurantId, tableSessionId, now);
        if (!table.isActive() || table.getOperationalStatus() != TableOperationalStatus.OCCUPIED
                || !main.isOpenAt(now, held.protectsMain()) || !guest.isUsableAt(now, held.protectsGuest(guestSessionId))) {
            throw new InvalidGuestSessionException();
        }
        return new ValidatedGuestContext(restaurantId, table.getId(), tableSessionId, guestSessionId, now);
    }

    public record ValidatedGuestContext(UUID restaurantId, UUID tableId, UUID tableSessionId, UUID guestSessionId, Instant checkedAt) { }
}
