package com.restofy.menu.application.service;

import java.util.UUID;

/**
 * Where the customer menu gets the rating summary of a product. Rating data belongs to the Rating service: Menu does
 * not store it and never reads that service's database.
 *
 * <p>The caller passes a restaurant id that was verified by the trusted restaurant resolver and a product that was
 * just confirmed to be visible to customers, never a value taken from the request. An implementation must send the
 * restaurant through a trusted service-to-service identity (not available yet), must finish within a bounded time
 * (own connect and read timeouts), and signals any failure by throwing or by answering
 * {@link RatingSummary#unavailable()}: it must never answer a made-up number.
 *
 * <p>No real implementation exists, because the Rating service itself is not built. The production default is
 * {@code UnavailableRatingSummaryPort}.
 */
public interface RatingSummaryPort {

    RatingSummary summaryFor(UUID restaurantId, UUID productId);
}
