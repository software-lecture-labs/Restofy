package com.restofy.auth.domain.enums;

/**
 * {@code MFA_PENDING}: an ADMIN proved the password and must still pass MFA. {@code PASSWORD_PENDING}: the member
 * signed in with a password an ADMIN set and must choose their own. Only {@code AUTHENTICATED} reaches other services.
 */
public enum SessionAuthState {
    AUTHENTICATED, MFA_PENDING, PASSWORD_PENDING
}
