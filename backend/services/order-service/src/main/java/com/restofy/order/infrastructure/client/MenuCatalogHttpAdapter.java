package com.restofy.order.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.restofy.order.application.port.MenuCatalogPort;
import com.restofy.order.application.port.ProductSnapshot;
import com.restofy.order.application.port.ProductSnapshot.OptionType;
import com.restofy.order.application.port.ProductSnapshot.ProductOption;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import com.restofy.order.infrastructure.config.CartProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("order-menu")
class MenuCatalogHttpAdapter implements MenuCatalogPort {

    private static final String LOOKUP = "/api/v1/internal/menu/catalog/products:lookup";
    private static final int MAX_PRODUCT_IDS = 50;

    private final URI endpoint;
    private final RestClient http;
    private final JsonMapper json;
    private final OrderServiceRequestSigner signer;
    private final UUID restaurant;

    MenuCatalogHttpAdapter(@Value("${MENU_SERVICE_URL:}") String base,
                           @Value("${ORDER_MENU_CALLER_KEY_ID:}") String keyId,
                           @Value("${ORDER_MENU_CALLER_PRIVATE_KEY:}") String privateKey,
                           @Value("${ORDER_MENU_CALLER_RESTAURANT_ID:}") String restaurantId,
                           CartProperties cart, Clock clock, RestClient.Builder http, JsonMapper json) {
        if (cart.maxLines() > MAX_PRODUCT_IDS) {
            throw new IllegalStateException("order-menu requires restofy.cart.max-lines to be at most 50");
        }
        endpoint = QrSessionHttpAdapter.httpsOrigin(base, "MENU_SERVICE_URL", "order-menu").resolve(LOOKUP);
        signer = new OrderServiceRequestSigner(clock, keyId, privateKey, "ORDER_MENU_CALLER",
                "restofy-menu", "menu:catalog-read");
        restaurant = configuredRestaurant(restaurantId);
        JsonMapper catalogJson = json.rebuild()
                .withCoercionConfig(LogicalType.Integer, config -> config
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail))
                .build();
        this.http = http.clone().configureMessageConverters(converters ->
                converters.withJsonConverter(new JacksonJsonHttpMessageConverter(catalogJson))).build();
        this.json = json;
    }

    @Override
    public Map<UUID, ProductSnapshot> findProducts(UUID restaurantId, Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> ids = new LinkedHashSet<>(productIds);
        if (!restaurant.equals(restaurantId) || ids.contains(null) || ids.size() > MAX_PRODUCT_IDS) {
            throw unavailable();
        }
        byte[] body = json.writeValueAsBytes(new Lookup(restaurant, List.copyOf(ids)));
        try {
            var result = http.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, signer.authorization(restaurant, HttpMethod.POST, LOOKUP, body))
                    .body(output -> output.write(body))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) {
                            throw unavailable();
                        }
                        return response.bodyTo(CatalogReply.class);
                    });
            return snapshots(result, ids);
        } catch (RestClientException | IllegalArgumentException _) {
            throw unavailable();
        }
    }

    private static Map<UUID, ProductSnapshot> snapshots(CatalogReply reply, Set<UUID> ids) {
        if (reply == null || reply.products() == null || reply.products().size() > ids.size()) {
            throw unavailable();
        }
        Map<UUID, ProductSnapshot> result = new HashMap<>();
        for (CatalogProduct product : reply.products()) {
            if (product == null || !ids.contains(product.productId()) || result.containsKey(product.productId())) {
                throw unavailable();
            }
            result.put(product.productId(), product.snapshot());
        }
        return Map.copyOf(result);
    }

    private static UUID configuredRestaurant(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equals(value)) {
                return id;
            }
        } catch (IllegalArgumentException _) {
            // Configuration errors do not echo values.
        }
        throw new IllegalStateException("Invalid ORDER_MENU_CALLER_RESTAURANT_ID");
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "Menu catalog is currently unavailable");
    }

    private record Lookup(UUID restaurantId, List<UUID> productIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CatalogReply(List<CatalogProduct> products) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CatalogProduct(UUID productId, String name, Boolean published, Boolean available, String currency,
                                  Long basePriceGrossMinor, Integer taxRateBps, String priceIssue,
                                  List<CatalogOption> options, String priceVersion) {

        ProductSnapshot snapshot() {
            if (published == null || available == null || options == null
                    || (basePriceGrossMinor == null && (priceIssue == null || priceIssue.isBlank()))
                    || (basePriceGrossMinor != null && priceIssue != null)) {
                throw unavailable();
            }
            List<ProductOption> validOptions = new ArrayList<>();
            Set<UUID> optionIds = new HashSet<>();
            boolean priced = basePriceGrossMinor != null && taxRateBps != null;
            for (CatalogOption option : options) {
                if (option == null || option.optionId() == null || !optionIds.add(option.optionId())) {
                    throw unavailable();
                }
                option.validate();
                if (option.priceDeltaMinor() != null) {
                    validOptions.add(option.snapshot());
                } else if (option.active()) {
                    priced = false;
                }
            }
            return new ProductSnapshot(productId, name, published.booleanValue(), available && priced, currency,
                    basePriceGrossMinor, taxRateBps, validOptions, priceVersion);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CatalogOption(UUID optionId, String name, OptionType type, Long priceDeltaMinor,
                                 Integer minQuantity, Integer maxQuantity, Boolean active) {

        void validate() {
            if (name == null || name.isBlank() || name.length() > ProductSnapshot.MAX_OPTION_NAME_LENGTH
                    || type == null || minQuantity == null || maxQuantity == null || active == null
                    || minQuantity < 0 || maxQuantity < minQuantity || maxQuantity > ProductSnapshot.MAX_OPTION_QUANTITY) {
                throw unavailable();
            }
        }

        ProductOption snapshot() {
            return new ProductOption(optionId, name, type, priceDeltaMinor, minQuantity, maxQuantity, active);
        }
    }
}
