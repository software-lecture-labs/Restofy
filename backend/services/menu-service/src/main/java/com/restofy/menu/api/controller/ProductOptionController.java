package com.restofy.menu.api.controller;

import com.restofy.menu.api.dto.request.CreateProductOptionRequest;
import com.restofy.menu.api.dto.request.UpdateProductOptionRequest;
import com.restofy.menu.api.dto.response.PageResponse;
import com.restofy.menu.api.dto.response.ProductOptionResponse;
import com.restofy.menu.application.mapper.ProductOptionMapper;
import com.restofy.menu.application.security.AdminActor;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.service.ProductOptionQueryService;
import com.restofy.menu.application.service.ProductOptionService;
import com.restofy.menu.application.service.UpdateProductOptionCommand;
import com.restofy.menu.domain.entity.ProductOption;
import com.restofy.menu.domain.enums.OptionType;
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
 * Management API for the removable / mandatory base ingredients and the optional extras of one product. The Gateway
 * forwards /api/v1/menu/** without stripping the prefix, so the full path is mapped here. There is deliberately no
 * DELETE: an option is disabled with {@code isActive=false} and can be re-enabled. Every handler takes the
 * verified ADMIN actor as its FIRST parameter, so 401/403 are decided before anything else is read or validated.
 * This is not a public customer endpoint.
 */
@RestController
@RequestMapping("/api/v1/menu/products/{productId}/options")
public class ProductOptionController {

    private final ProductOptionService options;
    private final ProductOptionQueryService queries;

    public ProductOptionController(ProductOptionService options, ProductOptionQueryService queries) {
        this.options = options;
        this.queries = queries;
    }

    /** Disabled options included; filter with {@code isActive} and {@code type}. Paged in a fixed, repeatable order. */
    @GetMapping
    public PageResponse<ProductOptionResponse> list(
            @AdminActor AuthenticatedActor actor,
            @PathVariable UUID productId,
            @RequestParam(name = "isActive", required = false) Boolean isActive,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + OptionPageParameters.DEFAULT_SIZE) int size) {
        OptionType filter = OptionPageParameters.type(type);
        var result = queries.list(actor, productId, isActive, filter, OptionPageParameters.of(page, size));
        return PageResponse.of(result, ProductOptionMapper::toResponse);
    }

    @GetMapping("/{optionId}")
    public ProductOptionResponse get(@AdminActor AuthenticatedActor actor, @PathVariable UUID productId,
            @PathVariable UUID optionId) {
        return ProductOptionMapper.toResponse(queries.get(actor, productId, optionId));
    }

    @PostMapping
    public ResponseEntity<ProductOptionResponse> create(@AdminActor AuthenticatedActor actor, @PathVariable UUID productId,
            @Valid @RequestBody CreateProductOptionRequest request) {
        var spec = new ProductOption.Spec(OptionType.valueOf(request.type()), request.name(), request.removable(),
                request.priceDelta(), request.maxQuantity() == null ? null : request.maxQuantity().intValue(),
                request.sortOrder() == null ? 0 : request.sortOrder().intValue());
        ProductOption created = options.create(actor, productId, spec);
        return ResponseEntity.created(URI.create("/api/v1/menu/products/" + productId + "/options/" + created.getId()))
                .body(ProductOptionMapper.toResponse(created));
    }

    @PatchMapping("/{optionId}")
    public ProductOptionResponse update(@AdminActor AuthenticatedActor actor, @PathVariable UUID productId,
            @PathVariable UUID optionId, @Valid @RequestBody UpdateProductOptionRequest request) {
        var command = new UpdateProductOptionCommand(request.name(), request.priceDelta(), request.removable(),
                request.maxQuantity() == null ? null : request.maxQuantity().intValue(),
                request.sortOrder() == null ? null : request.sortOrder().intValue(), request.isActive(), request.version());
        return ProductOptionMapper.toResponse(options.update(actor, productId, optionId, command));
    }
}
