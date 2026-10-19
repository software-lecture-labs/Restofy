package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.request.GuestActivityRequest;
import com.restofy.qr.api.dto.response.GuestActivityResponse;
import com.restofy.qr.domain.exception.InvalidGuestSessionException;
import com.restofy.qr.infrastructure.persistence.repository.GuestSessionRepository;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestSessionActivityService {
    private final GuestSessionValidationService validation;
    private final TableSessionRepository sessions;
    private final GuestSessionRepository guests;

    public GuestSessionActivityService(GuestSessionValidationService validation, TableSessionRepository sessions,
                                         GuestSessionRepository guests) {
        this.validation = validation;
        this.sessions = sessions;
        this.guests = guests;
    }

    @Transactional
    public GuestActivityResponse recordActivity(GuestActivityRequest request) {
        // Supplied IDs are only expected operation context, never authority.
        // Validation joins this transaction and holds the parent lock until commit.
        var context = validation.validate(request.guestToken(), request.restaurantId(), request.tableSessionId(), request.guestSessionId());
        var main = sessions.findByRestaurantIdAndId(context.restaurantId(), context.tableSessionId()).orElseThrow(InvalidGuestSessionException::new);
        var guest = guests.findByRestaurantIdAndTableSessionIdAndId(context.restaurantId(), context.tableSessionId(), context.guestSessionId())
                .orElseThrow(InvalidGuestSessionException::new);
        main.recordActivityAt(context.checkedAt());
        guest.recordActivityAt(context.checkedAt());
        sessions.flush();
        return new GuestActivityResponse(context.restaurantId(), main.getId(), guest.getId(), main.getVersion(), guest.getVersion(),
                guest.getLastActivityAt(), main.getIdleExpiresAt(), guest.getExpiresAt());
    }
}
