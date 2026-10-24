package com.restofy.auth.infrastructure.security;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.restofy.auth.api.dto.request.*;
import com.restofy.auth.api.dto.response.*;
import com.restofy.auth.application.service.MfaEnrollmentService.Enrollment;
import com.restofy.auth.application.service.MfaVerificationService.Completion;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class MfaAuditTests {
    @Test
    void auditWaitsForCommitAndSensitiveDtosNeverExposeTheirContentsInLogs() {
        String secret = "TESTONLYBASE32SECRET";
        String otp = "654321";
        String recovery = "TEST-ONLY-CODE-ABCD-EFGH";
        String uri = "otpauth://test-only";
        Object[] sensitive = {new Enrollment(secret, uri, Instant.EPOCH), new TotpRequest(otp),
                new RecoveryRequest(recovery), new RecoveryRegenerationRequest(secret, otp, null),
                new MfaCompletionResponse(null, List.of(recovery)), new RecoveryCodesResponse(List.of(recovery)),
                new Completion(null, List.of(recovery))};
        Logger logger = (Logger) LoggerFactory.getLogger(MfaAuditLogger.class);
        var previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        MfaAuditLogger audit = new MfaAuditLogger(new MutableAuthClock());
        UUID user = UUID.randomUUID(), session = UUID.randomUUID();
        try {
            TransactionSynchronizationManager.initSynchronization();
            audit.publish(MfaAuditLogger.Event.VERIFIED, user, session);
            assertThat(appender.list).isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            TransactionSynchronizationManager.clearSynchronization();
            assertThat(appender.list).hasSize(1);
            for (Object value : sensitive) { logger.info("{}", value); }
            audit.cipherFailure(user, session);
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                    .doesNotContain(secret, otp, recovery, uri));
            appender.list.clear();
            TransactionSynchronizationManager.initSynchronization();
            audit.publish(MfaAuditLogger.Event.ENROLL_CONFIRMED, user, session);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(appender.list).isEmpty();
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) { TransactionSynchronizationManager.clearSynchronization(); }
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }
}
