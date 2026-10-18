package com.restofy.menu.api.controller;

import com.restofy.menu.api.dto.request.ChangeProductActiveRequest;
import com.restofy.menu.api.dto.request.ChangeProductAvailabilityRequest;
import com.restofy.menu.api.dto.request.ChangeProductPublicationRequest;
import com.restofy.menu.api.dto.request.CreateProductRequest;
import com.restofy.menu.api.dto.request.UpdateProductRequest;
import com.restofy.menu.api.dto.response.PageResponse;
import com.restofy.menu.api.dto.response.ProductResponse;
import com.restofy.menu.application.mapper.ProductMapper;
import com.restofy.menu.application.security.AdminActor;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.service.ChangeAvailabilityCommand;
import com.restofy.menu.application.service.CreateProductCommand;
import com.restofy.menu.application.service.NutritionChange;
import com.restofy.menu.application.service.ProductFilter;
import com.restofy.menu.application.service.ProductQueryService;
import com.restofy.menu.application.service.ProductService;
import com.restofy.menu.application.service.ProductView;
import com.restofy.menu.application.service.UpdateProductCommand;
import com.restofy.menu.domain.entity.Product;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Management API for products. The Gateway forwards /api/v1/menu/** without stripping the prefix, so
 * the full path is mapped here. Rules live in {@link ProductService}. There is deliberately no
 * DELETE: a product is archived through {@code PATCH /{id}/active}. Every handler takes the verified ADMIN actor
 * as its FIRST parameter, so 401/403 are decided before the body is parsed or validated.
 */
@RestController
@RequestMapping("/api/v1/menu/products")
public class ProductController {

    private final ProductService products;
    private final ProductQueryService queries;

    public ProductController(ProductService products, ProductQueryService queries) {
        this.products = products;
        this.queries = queries;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@AdminActor AuthenticatedActor actor, @Valid @RequestBody CreateProductRequest request) {
        ProductView created = products.create(actor, new CreateProductCommand(request.categoryId(), request.name(),
                request.description(), request.ingredientsText(), request.basePrice(), request.currency(),
                request.vatRate(), request.priceIncludesVat(),
                new Product.Nutrition(request.calories(), request.weightGrams(), request.estimatedPrepMinutes())));
        return ResponseEntity.created(URI.create("/api/v1/menu/products/" + created.product().getId()))
                .body(ProductMapper.toResponse(created));
    }

    /**
     * Management list of the caller's restaurant. {@code categoryId} may be a main category (its own
     * products and, unless {@code directOnly=true}, those of its subcategories) or a subcategory.
     */
    @GetMapping
    public PageResponse<ProductResponse> list(
            @AdminActor AuthenticatedActor actor,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "false") boolean directOnly,
            @RequestParam(name = "isActive", required = false) Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + ProductPageParameters.DEFAULT_SIZE) int size,
            @RequestParam(required = false) String sort) {
        var result = queries.list(actor, new ProductFilter(categoryId, directOnly, isActive),
                ProductPageParameters.of(page, size, sort));
        return PageResponse.of(result, ProductMapper::toResponse);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@AdminActor AuthenticatedActor actor, @PathVariable UUID id) {
        return ProductMapper.toResponse(queries.get(actor, id));
    }

    @PatchMapping("/{id}")
    public ProductResponse update(@AdminActor AuthenticatedActor actor, @PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequest request) {
        return ProductMapper.toResponse(products.update(actor, id, new UpdateProductCommand(request.categoryId(),
                request.name(), request.description(), request.ingredientsText(), request.imageUrl(), request.basePrice(),
                request.currency(), request.vatRate(), request.priceIncludesVat(),
                new NutritionChange(request.calories(), request.weightGrams(), request.estimatedPrepMinutes()),
                request.version())));
    }

    @PatchMapping("/{id}/active")
    public ProductResponse changeActive(@AdminActor AuthenticatedActor actor, @PathVariable UUID id,
            @Valid @RequestBody ChangeProductActiveRequest request) {
        return ProductMapper.toResponse(products.changeActive(actor, id, request.isActive(), request.version()));
    }

    /** Publish or hide; independent of archiving and availability. Uses the product version. */
    @PatchMapping("/{id}/publication")
    public ProductResponse changePublication(@AdminActor AuthenticatedActor actor, @PathVariable UUID id,
            @Valid @RequestBody ChangeProductPublicationRequest request) {
        return ProductMapper.toResponse(products.changePublication(actor, id, request.isPublished(), request.version()));
    }

    /** Open or close for ordering; independent of archiving and publication. Uses the availability version. */
    @PatchMapping("/{id}/availability")
    public ProductResponse changeAvailability(@AdminActor AuthenticatedActor actor, @PathVariable UUID id,
            @Valid @RequestBody ChangeProductAvailabilityRequest request) {
        return ProductMapper.toResponse(products.changeAvailability(actor, id, new ChangeAvailabilityCommand(
                request.status(), request.reasonCode(), request.reasonText(), request.version())));
    }

}
