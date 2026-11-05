package com.restofy.menu.api.dto.response;

import java.util.UUID;

/** A category as a customer needs to name it: identifier and name, nothing about the restaurant or its state. */
public record PublicCategoryRef(UUID id, String name) {
}
