package com.restofy.menu.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The customer product detail. Deliberately NOT transactional: the product, its options and its visibility are read by
 * {@link PublicMenuService} in one short read-only REPEATABLE READ transaction that is finished before the rating
 * source is asked, so a slow or failing rating source never holds a database transaction (or a connection) open.
 *
 * <p>The rating source is asked only for a product that was just confirmed visible, with the restaurant the trusted
 * resolver verified. A hidden, unknown or foreign product ends in the same "not found" before the rating port is
 * touched. A rating that cannot be had (the port is down, throws, answers nothing or answers something inconsistent)
 * never fails the detail: the product is returned with an UNAVAILABLE rating and no number.
 */
@Service
public class PublicProductDetailService {

    private static final Logger LOG = LoggerFactory.getLogger(PublicProductDetailService.class);

    private final PublicMenuService menu;
    private final RatingSummaryPort ratings;

    public PublicProductDetailService(PublicMenuService menu, RatingSummaryPort ratings) {
        this.menu = menu;
        this.ratings = ratings;
    }

    public PublicProductDetail detail(String restaurantRef, UUID productId) {
        PublicMenuService.ListedDetail listed = menu.listedDetail(restaurantRef, productId);
        return new PublicProductDetail(listed, ratingOf(listed));
    }

    private RatingSummary ratingOf(PublicMenuService.ListedDetail listed) {
        var product = listed.view().product();
        try {
            return RatingSummary.validated(ratings.summaryFor(product.getRestaurantId(), product.getId()));
        } catch (RuntimeException failure) {
            // Only the kind of failure is logged: no message, no ids, no payload.
            LOG.warn("The rating summary is unavailable ({}).", failure.getClass().getSimpleName());
            return RatingSummary.unavailable();
        }
    }
}
