package com.restofy.auth.infrastructure.security;

import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.restofy.auth.api.dto.request.CreateStaffRequest;
import com.restofy.auth.api.dto.request.UpdateStaffRequest;
import com.restofy.auth.domain.enums.StaffRole;
import com.restofy.auth.support.MutableAuthClock;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;

class StaffAuditTests {
    @Test
    void successfulAuditWaitsForCommitAndRollbackDoesNotProduceSuccess() {
        Logger logger = (Logger) LoggerFactory.getLogger(StaffAuditLogger.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        UUID actor = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        StaffAuditLogger audit = new StaffAuditLogger(new MutableAuthClock());
        try {
            TransactionSynchronizationManager.initSynchronization();
            audit.publish(StaffAuditLogger.Event.CREATED, actor, user, StaffRole.WAITER, true);
            assertThat(appender.list).isEmpty();
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            TransactionSynchronizationManager.clearSynchronization();
            assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                    .contains("staff_event=CREATED", actor.toString(), user.toString(), "role=WAITER", "active=true")
                    .doesNotContain("email", "password", "token", "cookie"));
            appender.list.clear();
            TransactionSynchronizationManager.initSynchronization();
            audit.publish(StaffAuditLogger.Event.UPDATED, actor, user, StaffRole.KITCHEN, false);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(appender.list).isEmpty();
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    @Test
    void administrationRequestsNeverRenderSensitiveFieldsInToString() {
        String email = "private@example.com";
        String password = "Never-log-this-password-123";
        assertThat(new CreateStaffRequest(email, password, StaffRole.ADMIN).toString()).doesNotContain(email, password);
        assertThat(new UpdateStaffRequest(email, StaffRole.WAITER, true, 0L).toString()).doesNotContain(email);
    }
}
