package com.restofy.menu.application.service;

import com.restofy.menu.application.security.TrustedKitchenCaller;
import com.restofy.menu.application.security.TrustedKitchenCallerProvider;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.infrastructure.persistence.repository.ClosedProductRow;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Answers the Kitchen service's question "which products are closed right now?" (request KITCHEN-REQUEST-01). Menu is the
 * only author of availability; this is a read-only view of it, never a copy that Kitchen has to keep.
 *
 * <p>There is no HTTP entry point yet and the production caller provider is fail-closed, so in production every query is
 * refused until a verified service identity exists (Auth D-09, open). It uses the same trusted caller as the Kitchen
 * commands: the restaurant comes only from the verified caller, never from the request.
 *
 * <ul>
 *   <li>Order: the caller is verified first, then the request is validated, then the data is read.</li>
 *   <li>Only the caller's restaurant, only the requested closed statuses ({@code AVAILABLE} is not a closed status and is
 *       refused), newest closing first and then by product id, so that a page is deterministic. The total is counted over
 *       the same filter.</li>
 *   <li>Archived and unpublished products are listed too, with their raw flags; leaving them out would be a business
 *       rule that is not decided (open question for Kitchen).</li>
 *   <li>The page and its total come from one repeatable-read snapshot, so they agree with each other. Nothing is cached:
 *       a command applied a moment ago is visible in the next answer.</li>
 *   <li>The {@code version} of an item is the availability version a command's {@code expectedVersion} is compared with.</li>
 * </ul>
 */
@Service
public class KitchenClosedProductsService {

    /** Provisional: Kitchen's own page size limit. The final limit is part of the open contract. */
    public static final int MAX_PAGE_SIZE = 100;

    private static final Set<AvailabilityStatus> CLOSED = EnumSet.of(AvailabilityStatus.OUT_OF_STOCK,
            AvailabilityStatus.TEMPORARILY_CLOSED);

    private final TrustedKitchenCallerProvider callers;
    private final ProductAvailabilityRepository availabilities;

    public KitchenClosedProductsService(TrustedKitchenCallerProvider callers, ProductAvailabilityRepository availabilities) {
        this.callers = callers;
        this.availabilities = availabilities;
    }

    /**
     * @param statuses the closed statuses to list; must be a non-empty subset of OUT_OF_STOCK and TEMPORARILY_CLOSED
     * @param page     zero-based
     * @param size     1..{@value #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public KitchenClosedProductsPage list(Collection<AvailabilityStatus> statuses, int page, int size) {
        TrustedKitchenCaller caller = callers.current().orElseThrow(UnauthenticatedException::new);
        Set<AvailabilityStatus> wanted = validated(statuses, page, size);

        Page<ClosedProductRow> rows = availabilities.findByStatuses(caller.restaurantId(), wanted, PageRequest.of(page, size));
        return new KitchenClosedProductsPage(rows.getContent().stream().map(KitchenClosedProductsService::view).toList(),
                rows.getTotalElements());
    }

    private static Set<AvailabilityStatus> validated(Collection<AvailabilityStatus> statuses, int page, int size) {
        if (statuses == null || statuses.isEmpty()) {
            throw new InvalidProductException("At least one closed status is required.");
        }
        Set<AvailabilityStatus> wanted = EnumSet.noneOf(AvailabilityStatus.class);
        for (AvailabilityStatus status : statuses) {
            if (status == null) {
                throw new InvalidProductException("A status must not be null.");
            }
            wanted.add(status);
        }
        if (!CLOSED.containsAll(wanted)) {
            throw new InvalidProductException("Only the closed statuses OUT_OF_STOCK and TEMPORARILY_CLOSED can be listed.");
        }
        if (page < 0) {
            throw new InvalidProductException("The page must not be negative.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidProductException("The page size must be between 1 and " + MAX_PAGE_SIZE + ".");
        }
        return wanted;
    }

    private static KitchenClosedProduct view(ClosedProductRow row) {
        return new KitchenClosedProduct(row.productId(), row.productName(), row.status(), row.reasonCode(), row.reasonText(),
                row.changedAt(), row.changedByUserId(), row.version(), row.active(), row.published());
    }
}
