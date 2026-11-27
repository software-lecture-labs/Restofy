package com.restofy.menu.api.controller;

import com.restofy.menu.api.dto.request.OrderCatalogLookupRequest;
import com.restofy.menu.api.dto.response.OrderCatalogResponse;
import com.restofy.menu.application.service.OrderCatalogService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Order service's catalog lookup: current name, price, VAT, options and availability of up to 50 products of one restaurant.
 * Internal only (the Gateway does not route {@code /api/v1/internal/**}); it is reached only through the signed-credential chain of
 * {@code OrderInternalSecurityConfiguration}, and {@link OrderCatalogService} refuses anything but a verified Order caller for
 * the restaurant it is authorized for. Read only: nothing is reserved, written or cached; the answer is {@code no-store}.
 */
@RestController
@RequestMapping("/api/v1/internal/menu/catalog")
class OrderCatalogController {

    private final OrderCatalogService catalog;

    OrderCatalogController(OrderCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping(path = "/products:lookup", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrderCatalogResponse> lookup(@Valid @RequestBody OrderCatalogLookupRequest request) {
        var found = catalog.lookup(request.restaurantId(), request.productIds());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(OrderCatalogResponse.of(found));
    }
}
