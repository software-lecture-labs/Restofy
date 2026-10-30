package com.restofy.auth.application.service;

import com.restofy.auth.infrastructure.client.MailClient;
import com.restofy.auth.infrastructure.persistence.repository.MailOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends claimed messages outside a database transaction, retaining the message ID across retries. */
@Component
@ConditionalOnProperty(prefix = "auth.mail-outbox", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
public class MailOutboxRelay {
    public static final int MAX_BATCH = 8;
    private static final Logger LOG = LoggerFactory.getLogger(MailOutboxRelay.class);
    private final MailOutboxRepository outbox;
    private final MailClient client;

    public MailOutboxRelay(MailOutboxRepository outbox, MailClient client) {
        this.outbox = outbox;
        this.client = client;
    }

    @Scheduled(fixedDelay = 1_000, initialDelay = 1_000)
    public void relayPending() {
        try {
            for (int index = 0; index < MAX_BATCH; index++) {
                if (!dispatchOne()) {
                    return;
                }
            }
        } catch (RuntimeException _) {
            // Database and transport exceptions may contain credentials or message content.
            LOG.warn("mail_outbox_event=RELAY_FAILED");
        }
    }

    public boolean dispatchOne() {
        var claimed = outbox.claim();
        if (claimed.isEmpty()) {
            return false;
        }
        var delivery = claimed.orElseThrow();
        try {
            client.deliver(delivery.message());
            outbox.delivered(delivery);
        } catch (RuntimeException _) {
            LOG.warn("mail_outbox_event=DELIVERY_RETRY message_id={} attempts={}",
                    delivery.message().messageId(), delivery.attempts());
            outbox.retry(delivery);
        }
        return true;
    }
}
