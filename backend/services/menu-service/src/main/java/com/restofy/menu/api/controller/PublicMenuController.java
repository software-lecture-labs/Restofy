package com.restofy.menu.api.controller;

import com.restofy.menu.api.dto.response.PageResponse;
import com.restofy.menu.api.dto.response.PublicCategoriesResponse;
import com.restofy.menu.api.dto.response.PublicProductCardResponse;
import com.restofy.menu.api.dto.response.PublicProductDetailResponse;
import com.restofy.menu.application.mapper.PublicMenuMapper;
import com.restofy.menu.application.service.PublicMenuService;
import com.restofy.menu.application.service.PublicProductDetailService;
import com.restofy.menu.application.service.PublicProductQuery;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The customer menu: read-only {@code GET}s, open without a login but only for a restaurant the trusted
 * {@code PublicRestaurantScopeResolver} verified (in production none until the QR / Admin verification exists).
 * It takes no actor and ignores identity headers. Reading the menu grants no right to order or to manage anything.
 * Answers are never cached: availability changes quickly and the URL names the restaurant.
 */
@RestController
@RequestMapping("/api/v1/menu/public/restaurants/{restaurantRef}")
class PublicMenuController {

    private final PublicMenuService menu;
    private final PublicProductDetailService details;

    PublicMenuController(PublicMenuService menu, PublicProductDetailService details) {
        this.menu = menu;
        this.details = details;
    }

    @GetMapping("/categories")
    ResponseEntity<PublicCategoriesResponse> categories(@PathVariable String restaurantRef) {
        var tree = menu.categories(restaurantRef).stream().map(PublicMenuMapper::toCategory).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PublicCategoriesResponse(tree));
    }

    @GetMapping("/products")
    ResponseEntity<PageResponse<PublicProductCardResponse>> products(
            @PathVariable String restaurantRef,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID subcategoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PublicProductPageParameters.DEFAULT_SIZE) int size,
            @RequestParam(required = false) String sort) {
        PublicProductPageParameters.validate(page, size);
        var result = menu.products(restaurantRef, new PublicProductQuery(categoryId, subcategoryId, q),
                PublicProductPageParameters.sort(sort), page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(PageResponse.of(result, card -> PublicMenuMapper.toCard(card.view(), card.summary())));
    }

    /**
     * One product in full. A hidden, unknown or foreign product and an unverified restaurant are the same 404; a
     * malformed product id is a 400. The rating is added after the database read and never fails the answer.
     */
    @GetMapping("/products/{productId}")
    ResponseEntity<PublicProductDetailResponse> product(@PathVariable String restaurantRef, @PathVariable UUID productId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(PublicMenuMapper.toDetail(details.detail(restaurantRef, productId)));
    }
}
