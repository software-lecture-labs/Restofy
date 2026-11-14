package com.restofy.menu.infrastructure.client;

import com.restofy.menu.application.service.RatingSummary;
import com.restofy.menu.application.service.RatingSummaryPort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Production default until a real Rating service and a trusted service identity (Auth D-09) exist. It makes no network
 * call and invents nothing: every product is "rating unavailable". Do not give it a fixed score, a zero or a cache.
 */
@Component
class UnavailableRatingSummaryPort implements RatingSummaryPort {

    @Override
    public RatingSummary summaryFor(UUID restaurantId, UUID productId) {
        return RatingSummary.unavailable();
    }
}
