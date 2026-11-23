package com.restofy.order.application.service;

import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.OrderView;
import com.restofy.order.domain.enums.PaymentStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.springframework.data.domain.Sort;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A validated list request. Every value that reaches the database comes from a closed set (enums, bounded numbers,
 * an allow-listed sort); nothing from the request is ever concatenated into a query.
 *
 * @param tableSessionId one table SESSION (a table is used by many sessions over time)
 * @param tableId        one physical table, across all of its sessions; never interchangeable with a session id
 * @param tableSessionIds many table sessions at once (staff search only, otherwise null); duplicates are removed
 * @param statuses       an explicit set of fulfillment statuses (staff search only, otherwise null); when present it
 *                       replaces the statuses of {@code view}
 */
public record OrderListQuery(UUID tableSessionId, UUID tableId, OrderView view, PaymentStatus paymentStatus,
                             int page, int limit, Sort.Direction direction, List<UUID> tableSessionIds,
                             Set<FulfillmentStatus> statuses) {

    /** The fulfillment statuses that select the orders: an explicit set if the search gave one, else the view's. */
    public Set<FulfillmentStatus> effectiveStatuses() {
        return statuses != null ? statuses : view.statuses();
    }

    /** The view name for the response, or null when an explicit status set was used instead of a view. */
    public String viewLabel() {
        return statuses != null ? null : view.name().toLowerCase(Locale.ROOT);
    }

    /** Parses and validates the raw query parameters; a bad value is a 422 with the offending parameter named. */
    public static OrderListQuery of(UUID tableSessionId, UUID tableId, String view, String paymentStatus,
                                    Integer page, Integer limit, String sort) {
        OrderView parsedView = view == null ? OrderView.ACTIVE : OrderView.parse(view)
                .orElseThrow(() -> invalid("view", "must be one of active, completed, cancelled"));
        PaymentStatus parsedPayment = null;
        if (paymentStatus != null) {
            try {
                parsedPayment = PaymentStatus.valueOf(paymentStatus.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException _) {
                throw invalid("paymentStatus", "must be one of NOT_REQUESTED, PENDING, PAID, FAILED, CANCELLED");
            }
        }
        int parsedPage = page == null ? 0 : page;
        if (parsedPage < 0) {
            throw invalid("page", "must not be negative");
        }
        int parsedLimit = limit == null ? PageLimits.DEFAULT_LIMIT : limit;
        if (parsedLimit < 1 || parsedLimit > PageLimits.MAX_LIMIT) {
            throw invalid("limit", "must be between 1 and " + PageLimits.MAX_LIMIT);
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (sort != null) {
            direction = switch (sort.trim().toLowerCase(Locale.ROOT)) {
                case "placedat,desc", "placedat:desc", "-placedat" -> Sort.Direction.DESC;
                case "placedat,asc", "placedat:asc", "placedat" -> Sort.Direction.ASC;
                default -> throw invalid("sort", "must be placedAt,asc or placedAt,desc");
            };
        }
        return new OrderListQuery(tableSessionId, tableId, parsedView, parsedPayment, parsedPage, parsedLimit,
                direction, null, null);
    }

    /**
     * Parses and validates a staff search body. Like {@link #of} but for many table sessions and an explicit set of
     * fulfillment statuses (for example READY alone for a delivery queue). A view and an explicit status set cannot be
     * combined: they would say two different things about the same filter.
     */
    public static OrderListQuery forSearch(List<UUID> tableSessionIds, UUID tableId, String view,
                                           List<String> fulfillmentStatuses, String paymentStatus, Integer page,
                                           Integer limit, String sort) {
        if (view != null && fulfillmentStatuses != null) {
            throw invalid("view", "cannot be combined with fulfillmentStatuses");
        }
        OrderListQuery base = of(null, tableId, view, paymentStatus, page, limit, sort);
        Set<FulfillmentStatus> statuses = null;
        if (fulfillmentStatuses != null) {
            statuses = EnumSet.noneOf(FulfillmentStatus.class);
            for (String name : fulfillmentStatuses) {
                try {
                    statuses.add(FulfillmentStatus.valueOf(name.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException _) {
                    throw invalid("fulfillmentStatuses",
                            "must be a non-empty set of RECEIVED, PREPARING, READY, SERVED, COMPLETED, CANCELLED");
                }
            }
        }
        List<UUID> sessions = tableSessionIds == null ? null : List.copyOf(new LinkedHashSet<>(tableSessionIds));
        return new OrderListQuery(null, tableId, base.view(), base.paymentStatus(), base.page(), base.limit(),
                base.direction(), sessions, statuses);
    }

    private static BusinessException invalid(String parameter, String rule) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid query parameter '" + parameter + "'",
                Map.of("parameter", parameter, "rule", rule));
    }
}
