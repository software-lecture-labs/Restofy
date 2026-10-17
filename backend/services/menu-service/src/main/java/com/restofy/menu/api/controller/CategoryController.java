package com.restofy.menu.api.controller;

import com.restofy.menu.api.dto.request.CreateCategoryRequest;
import com.restofy.menu.api.dto.request.UpdateCategoryRequest;
import com.restofy.menu.api.dto.response.CategoryResponse;
import com.restofy.menu.api.dto.response.PageResponse;
import com.restofy.menu.application.mapper.CategoryMapper;
import com.restofy.menu.application.security.AuthenticatedActor;
import com.restofy.menu.application.security.CurrentActorProvider;
import com.restofy.menu.application.service.CategoryQueryService;
import com.restofy.menu.application.service.CategoryService;
import com.restofy.menu.application.service.UpdateCategoryCommand;
import com.restofy.menu.domain.entity.Category;
import com.restofy.menu.domain.exception.UnauthenticatedException;
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
 * Management API for categories and subcategories. The Gateway forwards /api/v1/menu/** without stripping the
 * prefix, so the full path is mapped here. Rules live in {@link CategoryService}.
 */
@RestController
@RequestMapping("/api/v1/menu")
public class CategoryController {

    private final CategoryService categories;
    private final CategoryQueryService queries;
    private final CurrentActorProvider actors;

    public CategoryController(CategoryService categories, CategoryQueryService queries, CurrentActorProvider actors) {
        this.categories = categories;
        this.queries = queries;
        this.actors = actors;
    }

    @GetMapping("/categories")
    public PageResponse<CategoryResponse> list(
            @RequestParam(name = "isActive", required = false) Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageParameters.DEFAULT_SIZE) int size,
            @RequestParam(required = false) String sort) {
        var result = queries.listRoots(actor(), isActive, PageParameters.of(page, size, sort));
        return PageResponse.of(result, CategoryMapper::toResponse);
    }

    @GetMapping("/categories/{id}")
    public CategoryResponse get(@PathVariable UUID id) {
        return CategoryMapper.toResponse(queries.getRoot(actor(), id));
    }

    @GetMapping("/categories/{parentId}/subcategories")
    public PageResponse<CategoryResponse> listSubcategories(@PathVariable UUID parentId,
            @RequestParam(name = "isActive", required = false) Boolean isActive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + PageParameters.DEFAULT_SIZE) int size,
            @RequestParam(required = false) String sort) {
        var result = queries.listSubcategories(actor(), parentId, isActive, PageParameters.of(page, size, sort));
        return PageResponse.of(result, CategoryMapper::toResponse);
    }

    @GetMapping("/subcategories/{id}")
    public CategoryResponse getSubcategory(@PathVariable UUID id) {
        return CategoryMapper.toResponse(queries.getSubcategory(actor(), id));
    }

    @PostMapping("/categories")
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        Category created = categories.createRoot(actor(), request.name(), request.sortOrder());
        return ResponseEntity.created(URI.create("/api/v1/menu/categories/" + created.getId()))
                .body(CategoryMapper.toResponse(created));
    }

    @PatchMapping("/categories/{id}")
    public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
        return CategoryMapper.toResponse(categories.updateRoot(actor(), id, command(request)));
    }

    @PostMapping("/categories/{parentId}/subcategories")
    public ResponseEntity<CategoryResponse> createSubcategory(@PathVariable UUID parentId,
            @Valid @RequestBody CreateCategoryRequest request) {
        Category created = categories.createSubcategory(actor(), parentId, request.name(), request.sortOrder());
        return ResponseEntity.created(URI.create("/api/v1/menu/subcategories/" + created.getId()))
                .body(CategoryMapper.toResponse(created));
    }

    @PatchMapping("/subcategories/{id}")
    public CategoryResponse updateSubcategory(@PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
        return CategoryMapper.toResponse(categories.updateSubcategory(actor(), id, command(request)));
    }

    private static UpdateCategoryCommand command(UpdateCategoryRequest request) {
        return new UpdateCategoryCommand(request.name(), request.sortOrder(), request.isActive(), request.version());
    }

    private AuthenticatedActor actor() {
        return actors.current().orElseThrow(UnauthenticatedException::new);
    }
}
