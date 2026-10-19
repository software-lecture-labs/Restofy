package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.GuestSessionResponse;
import com.restofy.qr.api.dto.response.OpenedGuestSessionResponse;
import com.restofy.qr.domain.entity.GuestSession;
import com.restofy.qr.domain.entity.QrScanEvent;
import com.restofy.qr.domain.exception.TableSessionConflictException;
import com.restofy.qr.infrastructure.persistence.repository.GuestSessionRepository;
import com.restofy.qr.infrastructure.persistence.repository.QrScanEventRepository;
import com.restofy.qr.infrastructure.security.GuestTokenCodec;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestSessionOpeningService {
    private final TableSessionOpeningService mainSessions;
    private final GuestSessionRepository guests;
    private final GuestTokenCodec tokens;
    private final Clock clock;
    private final QrScanEventRepository scans;

    public GuestSessionOpeningService(TableSessionOpeningService mainSessions, GuestSessionRepository guests,
                                      GuestTokenCodec tokens, Clock clock, QrScanEventRepository scans) {
        this.mainSessions = mainSessions;
        this.guests = guests;
        this.tokens = tokens;
        this.clock = clock;
        this.scans = scans;
    }

    @Transactional
    public OpenedGuestSessionResponse open(String qrToken) {
        // The outer transaction keeps QR revalidation, parent lock, occupancy and
        // the first guest atomic. A repeated accepted scan always creates a guest.
        var opened = mainSessions.open(qrToken);
        var main = opened.session();
        var joinedAt = clock.instant();
        if (joinedAt.isBefore(main.openedAt())) {
            throw new TableSessionConflictException();
        }
        String token = tokens.generateToken();
        var guest = guests.saveAndFlush(new GuestSession(main.restaurantId(), main.id(), tokens.digest(token), joinedAt));
        scans.saveAndFlush(new QrScanEvent(main.restaurantId(), main.tableId(), opened.qrCodeId(), main.id(), guest.getId(), joinedAt));
        return new OpenedGuestSessionResponse(main,
                new GuestSessionResponse(guest.getId(), guest.getRestaurantId(), guest.getTableSessionId(), guest.getStatus(),
                        guest.getVersion(), guest.getJoinedAt(), guest.getLastActivityAt(), guest.getExpiresAt()), token);
    }
}
