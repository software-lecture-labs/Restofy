package com.restofy.menu.application.service;

import com.restofy.menu.application.security.TrustedKitchenCaller;
import com.restofy.menu.application.security.TrustedKitchenCallerProvider;
import com.restofy.menu.domain.entity.ProductAvailability;
import com.restofy.menu.domain.entity.ProductAvailabilityHistory;
import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.CommandIdConflictException;
import com.restofy.menu.domain.exception.InvalidProductException;
import com.restofy.menu.domain.exception.ProductAvailabilityMissingException;
import com.restofy.menu.domain.exception.ProductAvailabilityVersionConflictException;
import com.restofy.menu.domain.exception.ProductNotFoundException;
import com.restofy.menu.domain.exception.UnauthenticatedException;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityCommandRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityCommandRepository.Content;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityHistoryRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductAvailabilityRepository;
import com.restofy.menu.infrastructure.persistence.repository.ProductRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Applies availability commands of the Kitchen service. Menu stays the only owner of availability; Kitchen only asks.
 *
 * <p>There is no HTTP entry point yet and the production caller provider is fail-closed, so in production no command is
 * processed at all until a verified service identity exists. Tests use a test-only trusted caller.
 *
 * <p>Order of work, all in ONE transaction (any failure leaves no command record, no history entry and no change):
 * trusted caller, argument validation, the product in the caller's restaurant, then the command id is claimed. A
 * command id that was claimed before is a replay (same content: answered from the stored record, nothing applied) or a
 * conflict (different content). Otherwise the product's availability row is locked, an optional expected version is
 * checked, the change is applied and the history entry and the command record are written together. A command that
 * changes nothing is still recorded, so repeating it is recognised too.
 *
 * <p>Honest limits: without an expected version a delayed OLD command still overwrites a newer state, because the
 * database cannot tell it is old. Closing that needs ordering or freshness information from the real Kitchen contract.
 * A closed product is never reopened here except by an explicit AVAILABLE command; nothing reopens automatically.
 */
@Service
public class KitchenAvailabilityCommandService {

    static final String SOURCE = "KITCHEN";

    private final TrustedKitchenCallerProvider callers;
    private final ProductRepository products;
    private final ProductAvailabilityRepository availabilities;
    private final ProductAvailabilityHistoryRepository histories;
    private final ProductAvailabilityCommandRepository commands;

    public KitchenAvailabilityCommandService(TrustedKitchenCallerProvider callers, ProductRepository products,
            ProductAvailabilityRepository availabilities, ProductAvailabilityHistoryRepository histories,
            ProductAvailabilityCommandRepository commands) {
        this.callers = callers;
        this.products = products;
        this.availabilities = availabilities;
        this.histories = histories;
        this.commands = commands;
    }

    @Transactional
    public KitchenAvailabilityResult handle(KitchenAvailabilityCommand command) {
        TrustedKitchenCaller caller = callers.current().orElseThrow(UnauthenticatedException::new);
        Content content = validated(command, caller);
        UUID restaurantId = caller.restaurantId();
        products.findByIdAndRestaurantId(command.productId(), restaurantId).orElseThrow(ProductNotFoundException::new);

        if (!commands.claim(restaurantId, command.commandId(), content, Instant.now())) {
            return replay(restaurantId, command.commandId(), content);
        }
        return apply(caller, command, content);
    }

    /** Checks the command and returns its normalised content (the form that is stored and compared). */
    private static Content validated(KitchenAvailabilityCommand command, TrustedKitchenCaller caller) {
        if (command == null || command.commandId() == null || command.productId() == null) {
            throw new InvalidProductException("A command id and a product id are required.");
        }
        AvailabilityStatus status = command.targetStatus();
        if (status == null) {
            throw new InvalidProductException("Availability status is required.");
        }
        if (command.expectedVersion() != null && command.expectedVersion() < 0) {
            throw new InvalidProductException("The expected version must not be negative.");
        }
        String code = ProductAvailability.normalizeReasonCode(command.reasonCode());
        String text = ProductAvailability.normalizeReasonText(command.reasonText());
        if (status.requiresReason() && code == null) {
            throw new InvalidProductException("A reason code is required to close a product.");
        }
        return new Content(command.productId(), SOURCE, status.name(), code, text, command.expectedVersion(),
                caller.onBehalfOfUserId());
    }

    private KitchenAvailabilityResult replay(UUID restaurantId, UUID commandId, Content content) {
        var stored = commands.find(restaurantId, commandId)
                .orElseThrow(() -> new IllegalStateException("A claimed command id has no record."));
        if (!stored.content().equals(content)) {
            throw new CommandIdConflictException();
        }
        return new KitchenAvailabilityResult(KitchenAvailabilityResult.Outcome.valueOf(stored.outcome()), true,
                AvailabilityStatus.valueOf(stored.content().targetStatus()), stored.resultingVersion());
    }

    private KitchenAvailabilityResult apply(TrustedKitchenCaller caller, KitchenAvailabilityCommand command, Content content) {
        UUID restaurantId = caller.restaurantId();
        ProductAvailability availability = availabilities
                .findLockedByRestaurantIdAndProductId(restaurantId, command.productId())
                .orElseThrow(ProductAvailabilityMissingException::new);
        if (command.expectedVersion() != null && !command.expectedVersion().equals(availability.getVersion())) {
            throw new ProductAvailabilityVersionConflictException();
        }

        AvailabilityStatus before = availability.getStatus();
        boolean changed = availability.change(command.targetStatus(), content.reasonCode(), content.reasonText(),
                caller.onBehalfOfUserId());
        if (changed) {
            try {
                availability = availabilities.saveAndFlush(availability);
            } catch (ObjectOptimisticLockingFailureException _) {
                throw new ProductAvailabilityVersionConflictException();
            }
            histories.saveAndFlush(ProductAvailabilityHistory.changed(before, availability, command.commandId()));
        }
        var outcome = changed ? KitchenAvailabilityResult.Outcome.APPLIED : KitchenAvailabilityResult.Outcome.NO_CHANGE;
        commands.complete(restaurantId, command.commandId(), outcome.name(), availability.getVersion());
        return new KitchenAvailabilityResult(outcome, false, availability.getStatus(), availability.getVersion());
    }
}
