package com.restofy.menu.application.service;

/** Everything the product detail shows: the listed product with its options, and the rating summary. */
public record PublicProductDetail(PublicMenuService.ListedDetail listed, RatingSummary rating) {
}
