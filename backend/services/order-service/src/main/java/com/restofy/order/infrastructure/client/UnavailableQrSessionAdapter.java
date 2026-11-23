package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.GuestSessionContext;
import com.restofy.order.application.port.QrSessionPort;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Fail-closed stand-in used when neither the real QR profile nor dev/test is active.
 * It never validates anything, so no session can be forged in production.
 */
@Component
@Profile("((!dev & !test) | prod) & !order-qr")
public class UnavailableQrSessionAdapter implements QrSessionPort {

    @Override
    public Optional<GuestSessionContext> validateGuestSession(String guestSessionToken) {
        throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Session validation is currently unavailable");
    }

    @Override
    public Optional<GuestSessionContext.SessionState> currentSessionState(UUID restaurantId, UUID tableSessionId,
                                                                          UUID guestSessionId) {
        throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Session validation is currently unavailable");
    }
}
