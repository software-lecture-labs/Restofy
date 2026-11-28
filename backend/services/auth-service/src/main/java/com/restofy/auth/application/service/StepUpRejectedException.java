package com.restofy.auth.application.service;

import org.springframework.security.authentication.BadCredentialsException;

/**
 * The signed-in ADMIN's repeated password or second factor was wrong. It stays a {@link BadCredentialsException}
 * so the MFA attempt limiter counts it like any failed factor, but it answers with its own reason: the session itself
 * is still valid, and the screen must not treat this 401 as a sign-out.
 */
public class StepUpRejectedException extends BadCredentialsException {
    public StepUpRejectedException() {
        super("Step-up verification failed");
    }
}
