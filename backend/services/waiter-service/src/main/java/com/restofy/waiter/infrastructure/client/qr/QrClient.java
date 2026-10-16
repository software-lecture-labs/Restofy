package com.restofy.waiter.infrastructure.client.qr;

import com.restofy.waiter.domain.exception.DependencyException;
import com.restofy.waiter.domain.exception.DependencyFailure;
import com.restofy.waiter.domain.exception.SnapshotLimitExceededException;
import com.restofy.waiter.infrastructure.client.DownstreamCalls;
import com.restofy.waiter.infrastructure.config.ServiceUrlProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Adapter for the QR service, the owner of tables and table sessions. Everything it reads is checked
 * against the caller's restaurant, so data of another tenant is never passed on.
 * The contract is a DRAFT (docs/waiter/integration-contracts.md); QR has not implemented it yet.
 */
@Component
public class QrClient {

    public static final String DEPENDENCY = "qr";
    /** Largest restaurant the table snapshot supports; bigger restaurants fail instead of being cut short. */
    public static final int MAX_TABLES = 500;
    static final int PAGE_SIZE = 200;

    private final RestClient client;

    public QrClient(RestClient.Builder builder, ServiceUrlProperties urls) {
        this.client = builder.clone().baseUrl(urls.qrUrl().toString()).build();
    }

    /** Every table of the restaurant, read page by page with a fixed upper bound of requests. */
    public List<QrTableDto> listAllTables(UUID restaurantId) {
        List<QrTableDto> tables = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        int page = 0;
        while (true) {
            int requested = page;
            QrTablePageDto result = DownstreamCalls.execute(DEPENDENCY, () -> client.get()
                    .uri(uri -> uri.path("/internal/v1/tables")
                            .queryParam("restaurantId", restaurantId)
                            .queryParam("page", requested)
                            .queryParam("size", PAGE_SIZE)
                            .build())
                    .retrieve().body(QrTablePageDto.class));
            if (result.page() != requested || result.size() != PAGE_SIZE) {
                throw badResponse("page metadata does not match the request");
            }
            if (result.totalElements() > MAX_TABLES) {
                throw new SnapshotLimitExceededException(
                        "The restaurant has more than " + MAX_TABLES + " tables; the table snapshot is not supported");
            }
            if (result.items().size() > PAGE_SIZE) throw badResponse("page is larger than requested");
            for (QrTableDto table : result.items()) {
                requireTenant(restaurantId, table);
                if (!seen.add(table.id())) throw badResponse("duplicate table in the listing");
                tables.add(table);
            }
            if (tables.size() == result.totalElements()) return List.copyOf(tables);
            // Another page is needed, so this one must have made progress and must not overshoot the total.
            if (tables.size() > result.totalElements() || result.items().isEmpty()) {
                throw badResponse("table count does not match the total");
            }
            page++;
        }
    }

    public Optional<QrTableDto> findTable(UUID restaurantId, UUID tableId) {
        Optional<QrTableDto> table = DownstreamCalls.executeOptional(DEPENDENCY, () -> client.get()
                .uri(uri -> uri.path("/internal/v1/tables/{tableId}")
                        .queryParam("restaurantId", restaurantId).build(tableId))
                .retrieve().body(QrTableDto.class));
        table.ifPresent(found -> {
            requireTenant(restaurantId, found);
            if (!found.id().equals(tableId)) throw badResponse("table id does not match the request");
        });
        return table;
    }

    private static void requireTenant(UUID restaurantId, QrTableDto table) {
        if (!restaurantId.equals(table.restaurantId())) throw badResponse("table belongs to another restaurant");
    }

    private static DependencyException badResponse(String detail) {
        return new DependencyException(DEPENDENCY, DependencyFailure.BAD_RESPONSE,
                "qr service returned an unusable response: " + detail);
    }
}
