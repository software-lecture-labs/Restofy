package com.restofy.qr.infrastructure.security;

import com.restofy.qr.domain.SessionClosure.*;
import com.restofy.qr.domain.exception.SessionClosureUnavailableException;
import org.springframework.stereotype.Component;

/** Closed until real Order/Payment authority and complete, fenced coverage exist.
 * Called outside QR transactions. Timeout/not-found/local absence is not evidence.
 * Payment by itself cannot replace the separately verified staff approval.
 */
@Component
public class SessionClosureEvidenceAccess {
    public Evidence verify(Staff staff, Snapshot snapshot) { throw new SessionClosureUnavailableException(); }
}
