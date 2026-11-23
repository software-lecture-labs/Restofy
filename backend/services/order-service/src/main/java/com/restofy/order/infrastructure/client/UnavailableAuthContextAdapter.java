package com.restofy.order.infrastructure.client;

import com.restofy.order.application.port.AuthContextPort;
import com.restofy.order.application.port.StaffActor;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Fail-closed stand-in when staff authentication is not configured.
 */
@Component
@Profile("((!dev & !test) | prod) & !order-auth")
public class UnavailableAuthContextAdapter implements AuthContextPort {

    @Override
    public Optional<StaffActor> resolveStaff(String credential) {
        throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Staff authentication is currently unavailable");
    }
}
