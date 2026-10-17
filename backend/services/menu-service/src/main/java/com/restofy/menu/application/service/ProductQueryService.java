package com.restofy.menu.application.service;

import com.restofy.menu.application.security.AdminAccess;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.domain.entity.Product;
import com.restofy.menu.domain.exception.ProductNotFoundException;
import com.restofy.menu.infrastructure.persistence.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Management (ADMIN) reads. Archived rows are visible here and only here; a missing and a foreign
 * id are always the same "not found". Public customer reads are a later phase.
 */
@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository products;

    public ProductQueryService(ProductRepository products) {
        this.products = products;
    }

    public Product get(AuthenticatedActor actor, UUID id) {
        AuthenticatedActor admin = AdminAccess.requireAdmin(actor);
        return products.findByIdAndRestaurantId(id, admin.restaurantId()).orElseThrow(ProductNotFoundException::new);
    }
}
