package com.restofy.menu.application.service;

/** Partial update; a {@code null} field means "leave unchanged". */
public record UpdateCategoryCommand(String name, Integer sortOrder, Boolean active, long expectedVersion) {

    boolean changesNothing() {
        return name == null && sortOrder == null && active == null;
    }
}
