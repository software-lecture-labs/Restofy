package com.restofy.mail.support;

import com.restofy.testing.IsolatedPostgresTestInitializer;

/** Service context only; all safety checks live in the test-only shared module. */
public final class MailTestDatabaseInitializer extends IsolatedPostgresTestInitializer {
    public MailTestDatabaseInitializer() {
        super("Mail");
    }
}
