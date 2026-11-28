package com.restofy.menu.application.service;

import com.restofy.menu.domain.enums.AvailabilityStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * One closed product as Menu records it, for the Kitchen service (read only, one restaurant). Every value is Menu's own
 * record; nothing is derived or invented. {@code active} and {@code published} are the catalogue's two raw flags, kept
 * apart from availability and from each other: what Kitchen means by "published" (and whether an archived product
 * belongs on its list at all) is an open decision (request KITCHEN-REQUEST-01, questions 5 and 6), so neither is
 * interpreted here.
 *
 * @param version the product's AVAILABILITY version, the value {@code expectedVersion} of a command is compared with
 */
public record KitchenClosedProduct(UUID productId, String productName, AvailabilityStatus status, String reasonCode,
        String reasonText, Instant changedAt, UUID changedByUserId, long version, boolean active, boolean published) {
}
