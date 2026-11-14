package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.enums.CommandOutcome;
import com.restofy.menu.domain.enums.CommandSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The idempotency record of one ACCEPTED availability command, kept so that a repeated command is recognised, also when it
 * changed nothing. The key is the restaurant and the command id: a command id only means something inside one restaurant.
 * The content fields are the command as it arrived (already normalised), so a second command with the same id can be compared
 * with it: the same content is a replay, anything else is a conflict. {@code requestedByUserId} is a logical reference to the
 * Auth service. The row is written once when the id is claimed and then completed with what the command produced.
 */
@Entity
@Table(name = "product_availability_commands")
@IdClass(ProductAvailabilityCommand.Key.class)
public class ProductAvailabilityCommand {

    /** The primary key: tenant first, so no command can be reached from another restaurant. */
    public record Key(UUID restaurantId, UUID commandId) implements Serializable { }

    /** What a command asked for; the part that is compared when an id is used a second time. */
    public record Content(UUID productId, CommandSource source, AvailabilityStatus targetStatus, String reasonCode,
            String reasonText, Long expectedVersion, UUID requestedByUserId) { }

    @Id
    @Column(name = "restaurant_id", nullable = false, updatable = false)
    private UUID restaurantId;

    @Id
    @Column(name = "command_id", nullable = false, updatable = false)
    private UUID commandId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private CommandSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_status", nullable = false, updatable = false, length = 30)
    private AvailabilityStatus targetStatus;

    @Column(name = "reason_code", updatable = false, length = ProductAvailability.REASON_CODE_MAX_LENGTH)
    private String reasonCode;

    @Column(name = "reason_text", updatable = false, columnDefinition = "text")
    private String reasonText;

    @Column(name = "expected_version", updatable = false)
    private Long expectedVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CommandOutcome outcome;

    @Column(name = "resulting_version", nullable = false)
    private long resultingVersion;

    @Column(name = "requested_by_user_id", nullable = false, updatable = false)
    private UUID requestedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductAvailabilityCommand() { }

    /** A command that has just claimed its id. Until {@link #complete} it is provisionally {@code APPLIED} at version 0. */
    public ProductAvailabilityCommand(UUID restaurantId, UUID commandId, Content content, Instant createdAt) {
        this.restaurantId = Objects.requireNonNull(restaurantId, "restaurantId");
        this.commandId = Objects.requireNonNull(commandId, "commandId");
        this.productId = Objects.requireNonNull(content.productId(), "productId");
        this.source = Objects.requireNonNull(content.source(), "source");
        this.targetStatus = Objects.requireNonNull(content.targetStatus(), "targetStatus");
        this.reasonCode = content.reasonCode();
        this.reasonText = content.reasonText();
        this.expectedVersion = content.expectedVersion();
        this.requestedByUserId = Objects.requireNonNull(content.requestedByUserId(), "requestedByUserId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.outcome = CommandOutcome.APPLIED;
        this.resultingVersion = 0L;
    }

    /** Records what the command produced. */
    public void complete(CommandOutcome result, long version) {
        this.outcome = Objects.requireNonNull(result, "result");
        this.resultingVersion = version;
    }

    public Content content() {
        return new Content(productId, source, targetStatus, reasonCode, reasonText, expectedVersion, requestedByUserId);
    }

    public UUID getRestaurantId() { return restaurantId; }

    public UUID getCommandId() { return commandId; }

    public UUID getProductId() { return productId; }

    public CommandSource getSource() { return source; }

    public AvailabilityStatus getTargetStatus() { return targetStatus; }

    public String getReasonCode() { return reasonCode; }

    public String getReasonText() { return reasonText; }

    public Long getExpectedVersion() { return expectedVersion; }

    public CommandOutcome getOutcome() { return outcome; }

    public long getResultingVersion() { return resultingVersion; }

    public UUID getRequestedByUserId() { return requestedByUserId; }

    public Instant getCreatedAt() { return createdAt; }
}
