package com.restofy.auth.application.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.restofy.auth.infrastructure.client.MailClient;
import com.restofy.auth.infrastructure.client.MailClient.Message;
import com.restofy.auth.infrastructure.persistence.repository.MailOutboxRepository;
import com.restofy.auth.infrastructure.persistence.repository.MailOutboxRepository.Delivery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailOutboxRelayTests {
    private MailOutboxRepository outbox;
    private MailClient client;
    private MailOutboxRelay relay;

    @BeforeEach
    void setup() {
        outbox = mock(MailOutboxRepository.class);
        client = mock(MailClient.class);
        relay = new MailOutboxRelay(outbox, client);
    }

    @Test
    void emptyClaimDoesNotSendOrCompleteAnything() {
        when(outbox.claim()).thenReturn(Optional.empty());
        assertThat(relay.dispatchOne()).isFalse();
        verify(outbox).claim();
        verifyNoMoreInteractions(outbox);
        verifyNoInteractions(client);
    }

    @Test
    void sendsTheClaimedMessageAndPassesItsClaimIntactToCompletion() {
        Delivery delivery = delivery();
        when(outbox.claim()).thenReturn(Optional.of(delivery));
        assertThat(relay.dispatchOne()).isTrue();
        var ordered = inOrder(outbox, client);
        ordered.verify(outbox).claim();
        ordered.verify(client).deliver(same(delivery.message()));
        ordered.verify(outbox).delivered(same(delivery));
        verify(outbox, never()).retry(any());
    }

    @Test
    void transportFailureRetriesWithTheSameMessageIdentity() {
        Delivery first = delivery();
        Delivery second = new Delivery(first.message(), UUID.randomUUID(), 2);
        when(outbox.claim()).thenReturn(Optional.of(first), Optional.of(second));
        doThrow(new IllegalStateException("private-recipient-and-code"))
                .doNothing().when(client).deliver(first.message());
        assertThat(relay.dispatchOne()).isTrue();
        assertThat(relay.dispatchOne()).isTrue();
        verify(client, times(2)).deliver(same(first.message()));
        verify(outbox).retry(same(first));
        verify(outbox).delivered(same(second));
        verify(outbox, never()).delivered(first);
    }

    @Test
    void scheduledWorkStopsOnAnEmptyClaimAndNeverExceedsTheBatchBound() {
        Delivery delivery = delivery();
        when(outbox.claim()).thenReturn(Optional.of(delivery));
        relay.relayPending();
        verify(outbox, times(MailOutboxRelay.MAX_BATCH)).claim();
        verify(client, times(MailOutboxRelay.MAX_BATCH)).deliver(delivery.message());
        reset(outbox, client);
        when(outbox.claim()).thenReturn(Optional.empty());
        relay.relayPending();
        verify(outbox).claim();
        verifyNoInteractions(client);
    }

    @Test
    void transportAndRepositoryFailuresNeverExposeExceptionOrMessageContentInLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(MailOutboxRelay.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        Delivery delivery = delivery();
        String secret = "private-smtp-password-and-error";
        try {
            when(outbox.claim()).thenReturn(Optional.of(delivery));
            doThrow(new IllegalStateException(secret)).when(client).deliver(any());
            doThrow(new IllegalStateException(secret)).when(outbox).retry(any());
            assertThatCode(relay::relayPending).doesNotThrowAnyException();
            assertThat(appender.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain(secret, delivery.message().recipient(),
                        delivery.message().code(), delivery.message().templateKey());
            });
            assertThat(appender.list.getFirst().getFormattedMessage())
                    .contains(delivery.message().messageId().toString(), "attempts=1");
            appender.list.clear();
            when(outbox.claim()).thenThrow(new IllegalStateException(secret));
            assertThatCode(relay::relayPending).doesNotThrowAnyException();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("mail_outbox_event=RELAY_FAILED");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    @Test
    void completionFailureUsesTheSameClaimForRetry() {
        Delivery delivery = delivery();
        when(outbox.claim()).thenReturn(Optional.of(delivery));
        doThrow(new IllegalStateException("private-database-error")).when(outbox).delivered(delivery);
        assertThat(relay.dispatchOne()).isTrue();
        verify(outbox).retry(same(delivery));
    }

    private static Delivery delivery() {
        Message message = new Message(UUID.randomUUID(), "ADMIN_PASSWORD_RESET", "private@example.test", "tr",
                "731954", Instant.parse("2026-10-29T10:15:00Z"), Instant.parse("2026-10-29T10:01:00Z"));
        return new Delivery(message, UUID.randomUUID(), 1);
    }
}
