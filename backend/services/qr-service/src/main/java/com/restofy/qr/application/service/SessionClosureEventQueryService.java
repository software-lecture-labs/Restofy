package com.restofy.qr.application.service;

import com.restofy.qr.api.dto.response.SessionClosureEventPage;
import com.restofy.qr.infrastructure.persistence.repository.TableSessionClosureEventRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Durable closure events of one restaurant for the Waiter service, read by an ascending cursor. */
@Service
@Profile("qr-order-inbound")
public class SessionClosureEventQueryService {
    public static final int MAX_LIMIT = 200;

    private final TableSessionClosureEventRepository events;

    public SessionClosureEventQueryService(TableSessionClosureEventRepository events) {
        this.events = events;
    }

    @Transactional(readOnly = true)
    public SessionClosureEventPage after(UUID restaurantId, long cursor, int limit) {
        if (cursor < 0 || limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Cursor or limit is out of range.");
        }
        var found = events.findByRestaurantIdAndEventSeqGreaterThanOrderByEventSeqAsc(restaurantId, cursor, PageRequest.of(0, limit));
        var items = found.stream().map(event -> new SessionClosureEventPage.Event(event.getEventId(), event.getRestaurantId(),
                event.getTableId(), event.getTableSessionId(), event.getFinalStatus(), event.getOccurredAt())).toList();
        return new SessionClosureEventPage(items, found.isEmpty() ? cursor : found.getLast().getEventSeq());
    }
}
