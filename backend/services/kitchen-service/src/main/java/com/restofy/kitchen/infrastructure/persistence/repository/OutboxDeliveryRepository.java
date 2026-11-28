package com.restofy.kitchen.infrastructure.persistence.repository;

import com.restofy.kitchen.domain.entity.OutboxDelivery;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxDeliveryRepository extends JpaRepository<OutboxDelivery, UUID> {

	/**
	 * True for a delivery {@code d} that ended without the Order service ever accepting it: it refused this very request
	 * for good, or the stored event could not be sent at all. Every other ending is delivered or of UNKNOWN result:
	 * running out of attempts, and a reused event id, whose first content the Order service may have handled.
	 */
	String NEVER_ACCEPTED = "(d.destination = 'ORDER' and d.status = com.restofy.kitchen.domain.enums.DeliveryStatus.FAILED "
			+ "and ((d.lastErrorCode like 'HTTP_4%' and d.lastErrorCode <> 'HTTP_409_IDEMPOTENCY_CONFLICT') "
			+ "or d.lastErrorCode in ('UNREADABLE_EVENT', 'REASON_TEXT_MISSING', 'UNMAPPED_REASON')))";

	List<OutboxDelivery> findByRestaurantIdAndEventId(UUID restaurantId, UUID eventId);

	/**
	 * Ids of the deliveries to one destination that are due, oldest first. A plain read; claiming happens per delivery
	 * under a row lock. Another destination's deliveries are never returned for this one.
	 */
	@Query("select d.id from OutboxDelivery d where d.status = com.restofy.kitchen.domain.enums.DeliveryStatus.PENDING "
			+ "and d.destination = :destination and d.nextAttemptAt <= :now order by d.nextAttemptAt, d.id")
	List<UUID> findDueIds(@Param("now") Instant now, @Param("destination") String destination, Pageable limit);

	/** Same as {@link #findDueIds}, leaving out the deliveries of events of the given (non-empty) types. */
	@Query("select d.id from OutboxDelivery d where d.status = com.restofy.kitchen.domain.enums.DeliveryStatus.PENDING "
			+ "and d.destination = :destination and d.nextAttemptAt <= :now and not exists (select 1 from OutboxEvent e "
			+ "where e.id = d.eventId and e.eventType in :held) order by d.nextAttemptAt, d.id")
	List<UUID> findDueIdsExcept(@Param("now") Instant now, @Param("destination") String destination,
			@Param("held") Collection<String> held, Pageable limit);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = RowLocks.TIMEOUT_HINT, value = RowLocks.TIMEOUT_MILLIS))
	@Query("select d from OutboxDelivery d where d.id = :id")
	Optional<OutboxDelivery> findForUpdate(@Param("id") UUID id);
}
