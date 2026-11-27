package com.restofy.kitchen.application.contract;

import com.restofy.kitchen.application.contract.OrderCancellationEventMessage.Line;
import com.restofy.kitchen.application.contract.OrderCancellationEventMessage.Reason;
import com.restofy.kitchen.application.mapper.OrderCancellationEventReader;
import com.restofy.kitchen.domain.exception.InvalidOrderMessageException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The content rules of the Order service's cancellation events, without a database. */
class OrderCancellationEventMessageTests {

	private static final Instant AT = Instant.parse("2026-11-27T10:00:00Z");
	private static final UUID LINE = UUID.randomUUID();

	private static OrderCancellationEventMessage cancelled(String type, String scope, boolean confirm, List<Line> items,
			Instant decidedAt, String rejection, List<Reason> reasons, long version, String correlation) {
		return new OrderCancellationEventMessage(UUID.randomUUID(), type, 1, AT, correlation, UUID.randomUUID(), UUID.randomUUID(),
				version, UUID.randomUUID(), scope, confirm, items, decidedAt, rejection, reasons);
	}

	private static OrderCancellationEventMessage full(List<Line> items, List<Reason> reasons) {
		return cancelled("OrderCancelled", "FULL", false, items, AT, null, reasons, 3, null);
	}

	@Test
	void theFourKindsAsTheOrderServiceSendsThemAreFine() {
		assertNull(cancelled("OrderCancellationRequested", "PARTIAL", true, List.of(new Line(LINE, 1, 2)), null, null, null, 1, "c")
				.shapeProblem());
		assertNull(full(List.of(new Line(LINE, 2, 0)), List.of(new Reason("CHANGED_MIND"))).shapeProblem());
		assertNull(cancelled("OrderItemsCancelled", "PARTIAL", false, List.of(new Line(LINE, 1, 1)), AT, null, null, 2, null)
				.shapeProblem());
		assertNull(cancelled("OrderCancellationRejected", "FULL", false, List.of(new Line(LINE, 1, 1)), AT, "ORDER_CHANGED", null, 2,
				null).shapeProblem());
	}

	@Test
	void missingAndUnsupportedContentIsNamed() {
		assertEquals("MISSING_FIELD", cancelled("OrderCancelled", null, false, List.of(), AT, null, null, 1, null).shapeProblem());
		assertEquals("MISSING_FIELD", cancelled("OrderCancelled", "FULL", false, null, AT, null, null, 1, null).shapeProblem());
		assertEquals("UNSUPPORTED_EVENT", cancelled("OrderAccepted", "FULL", false, List.of(new Line(LINE, 1, 0)), AT, null, null,
				1, null).shapeProblem());
		assertEquals("BAD_SCOPE_OR_VERSION", cancelled("OrderCancelled", "ALL", false, List.of(new Line(LINE, 1, 0)), AT, null, null,
				1, null).shapeProblem());
		assertEquals("BAD_SCOPE_OR_VERSION", cancelled("OrderCancelled", "FULL", false, List.of(new Line(LINE, 1, 0)), AT, null, null,
				-1, null).shapeProblem());
		assertEquals("BAD_CORRELATION_ID", cancelled("OrderCancelled", "FULL", false, List.of(new Line(LINE, 1, 0)), AT, null, null,
				1, "x".repeat(65)).shapeProblem());
		assertEquals("BAD_CORRELATION_ID", cancelled("OrderCancelled", "FULL", false, List.of(new Line(LINE, 1, 0)), AT, null, null,
				1, "a\nb").shapeProblem());
	}

	@Test
	void linesMustBeFewUniquePositiveAndComplete() {
		List<Line> tooMany = IntStream.range(0, 201).mapToObj(i -> new Line(UUID.randomUUID(), 1, 0)).toList();
		for (List<Line> items : List.of(List.<Line>of(), tooMany, List.of(new Line(null, 1, 0)), List.of(new Line(LINE, 0, 0)),
				List.of(new Line(LINE, 1, -1)), List.of(new Line(LINE, 1, 0), new Line(LINE, 1, 0)),
				Collections.<Line>singletonList(null))) {
			assertEquals("BAD_ITEMS", full(items, List.of()).shapeProblem());
		}
	}

	@Test
	void decisionsAndRequestsCarryTheirOwnFields() {
		List<Line> one = List.of(new Line(LINE, 1, 0));
		assertEquals("BAD_DECISION", cancelled("OrderCancellationRequested", "FULL", true, one, AT, null, null, 1, null).shapeProblem());
		assertEquals("BAD_DECISION", cancelled("OrderCancellationRequested", "FULL", false, one, null, null, null, 1, null).shapeProblem());
		assertEquals("BAD_DECISION", cancelled("OrderCancelled", "FULL", false, one, null, null, List.of(), 1, null).shapeProblem());
		assertEquals("BAD_REJECTION_CODE", cancelled("OrderCancellationRejected", "FULL", false, one, AT, null, null, 1, null)
				.shapeProblem());
		assertEquals("BAD_REJECTION_CODE", cancelled("OrderCancellationRejected", "FULL", false, one, AT, "lower", null, 1, null)
				.shapeProblem());
		assertEquals("BAD_REJECTION_CODE", cancelled("OrderCancelled", "FULL", false, one, AT, "ORDER_CHANGED", null, 1, null)
				.shapeProblem());
		assertEquals("BAD_FULL_CANCELLATION", cancelled("OrderCancelled", "PARTIAL", false, one, AT, null, null, 1, null).shapeProblem());
		assertEquals("BAD_FULL_CANCELLATION", full(List.of(new Line(LINE, 1, 1)), null).shapeProblem());
	}

	@Test
	void reasonsAreFewCatalogueCodesAndJoinedForTheOrder() {
		List<Line> one = List.of(new Line(LINE, 1, 0));
		assertEquals("BAD_REASONS", full(one, Collections.singletonList(null)).shapeProblem());
		assertEquals("BAD_REASONS", full(one, List.of(new Reason(null))).shapeProblem());
		assertEquals("BAD_REASONS", full(one, List.of(new Reason("free text"))).shapeProblem());
		assertEquals("BAD_REASONS", full(one, IntStream.range(0, 11).mapToObj(i -> new Reason("CODE_" + i)).toList()).shapeProblem());

		assertNull(full(one, null).reasonCodes());
		assertNull(full(one, List.of()).reasonCodes());
		assertEquals("CHANGED_MIND,WAIT_TOO_LONG", full(one, List.of(new Reason("CHANGED_MIND"), new Reason("WAIT_TOO_LONG"),
				new Reason("CHANGED_MIND"))).reasonCodes());
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "[]", "null", "{\"eventId\":1}"})
	void theReaderRefusesWhatIsNotAValidEventWithoutEchoingIt(String payload) {
		var reader = new OrderCancellationEventReader();

		InvalidOrderMessageException failure = assertThrows(InvalidOrderMessageException.class, () -> reader.read(payload));

		assertEquals(1, failure.getViolations().size());
	}

	@Test
	void theReaderRefusesAnOversizedPayload() {
		var reader = new OrderCancellationEventReader();
		String huge = "{\"x\":\"" + "a".repeat(262_144) + "\"}";

		assertEquals(List.of("payload is too large"),
				assertThrows(InvalidOrderMessageException.class, () -> reader.read(huge)).getViolations());
	}
}
