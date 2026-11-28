package com.restofy.menu.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Body of the Order catalog lookup: {@code {"restaurantId":"<uuid>","productIds":["<uuid>", ...]}}. A field added to the same
 * contract version is ignored (the body is bound to the signature, so only Order can send it). {@code restaurantId} is never an authority by itself: it must equal the restaurant of the verified Order credential
 * (which in turn must be one that key is authorized for), otherwise the request is 403.
 */
public record OrderCatalogLookupRequest(@NotNull UUID restaurantId, @NotEmpty List<@NotNull UUID> productIds) {
}
