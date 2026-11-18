package com.restofy.auth.application.service.revocation;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Commits PREPARE only; callers throw before the protected business mutation. */
public final class RevocationPendingException extends ResponseStatusException {
    public RevocationPendingException() { super(HttpStatus.SERVICE_UNAVAILABLE, "Security transition pending; retry safely"); }
}
