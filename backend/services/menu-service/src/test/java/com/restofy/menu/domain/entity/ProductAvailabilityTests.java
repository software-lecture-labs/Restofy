package com.restofy.menu.domain.entity;

import com.restofy.menu.domain.enums.AvailabilityStatus;
import com.restofy.menu.domain.exception.InvalidProductException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure rules of the current availability record and of its history entries. */
class ProductAvailabilityTests {

    private static final UUID RESTAURANT = UUID.randomUUID();
    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OTHER_ADMIN = UUID.randomUUID();

    private static ProductAvailability initial() {
        return ProductAvailability.initial(RESTAURANT, PRODUCT, ADMIN);
    }

    @Test
    void initialStateIsAvailableWithoutAReasonAndKeepsTenantProductAndActor() {
        var availability = initial();
        assertEquals(AvailabilityStatus.AVAILABLE, availability.getStatus());
        assertNull(availability.getReasonCode());
        assertNull(availability.getReasonText());
        assertEquals(RESTAURANT, availability.getRestaurantId());
        assertEquals(PRODUCT, availability.getProductId());
        assertEquals(ADMIN, availability.getChangedByUserId());
        assertNotNull(availability.getChangedAt());
        assertNotNull(availability.getId());
    }

    @Test
    void closingKeepsReasonActorAndTime() {
        var availability = initial();
        var before = availability.getChangedAt();
        assertTrue(availability.change(AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "  no more tomatoes ", OTHER_ADMIN));
        assertEquals(AvailabilityStatus.OUT_OF_STOCK, availability.getStatus());
        assertEquals("SOLD_OUT", availability.getReasonCode());
        assertEquals("no more tomatoes", availability.getReasonText());
        assertEquals(OTHER_ADMIN, availability.getChangedByUserId());
        assertFalse(availability.getChangedAt().isBefore(before));
    }

    @Test
    void outOfStockAndTemporarilyClosedAreDistinctStates() {
        var availability = initial();
        availability.change(AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null, ADMIN);
        assertTrue(availability.change(AvailabilityStatus.TEMPORARILY_CLOSED, "SOLD_OUT", null, ADMIN));
        assertEquals(AvailabilityStatus.TEMPORARILY_CLOSED, availability.getStatus());
    }

    @Test
    void closedStatusesNeedAReasonCode() {
        var availability = initial();
        for (AvailabilityStatus closed : new AvailabilityStatus[] {AvailabilityStatus.OUT_OF_STOCK, AvailabilityStatus.TEMPORARILY_CLOSED}) {
            assertThrows(InvalidProductException.class, () -> availability.change(closed, null, "text only", ADMIN));
            assertThrows(InvalidProductException.class, () -> availability.change(closed, "  ", null, ADMIN));
        }
        assertEquals(AvailabilityStatus.AVAILABLE, availability.getStatus(), "a rejected change leaves the state untouched");
        assertNull(availability.getReasonCode());
    }

    @Test
    void reasonCodeShapeAndTextLengthAreEnforced() {
        var availability = initial();
        for (String bad : new String[] {"lower", "1ABC", "HAS SPACE", "WITH-DASH", "X".repeat(41)}) {
            assertThrows(InvalidProductException.class,
                    () -> availability.change(AvailabilityStatus.OUT_OF_STOCK, bad, null, ADMIN), bad);
        }
        String tooLong = "t".repeat(501);
        assertThrows(InvalidProductException.class,
                () -> availability.change(AvailabilityStatus.OUT_OF_STOCK, "OK_CODE", tooLong, ADMIN));
        assertTrue(availability.change(AvailabilityStatus.OUT_OF_STOCK, "X".repeat(40), "t".repeat(500), ADMIN));
    }

    @Test
    void statusIsRequired() {
        var missingStatus = initial();
        var missingActor = initial();
        assertThrows(InvalidProductException.class, () -> missingStatus.change(null, "CODE", null, ADMIN));
        assertThrows(NullPointerException.class, () -> missingActor.change(AvailabilityStatus.AVAILABLE, null, null, null));
    }

    @Test
    void reopeningClearsTheCurrentReasonButItStaysInTheHistoryEntry() {
        var availability = initial();
        availability.change(AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "gone", ADMIN);
        var closing = ProductAvailabilityHistory.changed(AvailabilityStatus.AVAILABLE, availability);

        assertTrue(availability.change(AvailabilityStatus.AVAILABLE, null, null, OTHER_ADMIN));
        var reopening = ProductAvailabilityHistory.changed(AvailabilityStatus.OUT_OF_STOCK, availability);

        assertNull(availability.getReasonCode());
        assertEquals("SOLD_OUT", closing.getReasonCode(), "the closing reason survives in its history entry");
        assertEquals("gone", closing.getReasonText());
        assertEquals(ADMIN, closing.getChangedByUserId());
        assertEquals(AvailabilityStatus.OUT_OF_STOCK, reopening.getFromStatus());
        assertEquals(AvailabilityStatus.AVAILABLE, reopening.getToStatus());
        assertEquals(OTHER_ADMIN, reopening.getChangedByUserId());
    }

    @Test
    void reopeningMayCarryAnOptionalNote() {
        var availability = initial();
        availability.change(AvailabilityStatus.TEMPORARILY_CLOSED, "CLEANING", null, ADMIN);
        availability.change(AvailabilityStatus.AVAILABLE, "RESTOCKED", "delivery arrived", ADMIN);
        assertEquals("RESTOCKED", availability.getReasonCode());
        assertEquals("delivery arrived", availability.getReasonText());
    }

    @Test
    void repeatingTheSameStateIsANoOpThatKeepsActorAndTime() {
        var availability = initial();
        availability.change(AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", "gone", ADMIN);
        var at = availability.getChangedAt();
        assertFalse(availability.change(AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", " gone ", OTHER_ADMIN));
        assertEquals(ADMIN, availability.getChangedByUserId());
        assertEquals(at, availability.getChangedAt());
        assertFalse(initial().change(AvailabilityStatus.AVAILABLE, null, null, OTHER_ADMIN));
    }

    @Test
    void aNewReasonForTheSameClosedStateIsARealChange() {
        var availability = initial();
        availability.change(AvailabilityStatus.OUT_OF_STOCK, "SOLD_OUT", null, ADMIN);
        assertTrue(availability.change(AvailabilityStatus.OUT_OF_STOCK, "SUPPLIER_DELAY", null, OTHER_ADMIN));
        assertEquals("SUPPLIER_DELAY", availability.getReasonCode());
        assertEquals(OTHER_ADMIN, availability.getChangedByUserId());
    }

    @Test
    void firstHistoryEntryHasNoPreviousStatus() {
        var entry = ProductAvailabilityHistory.initial(initial());
        assertNull(entry.getFromStatus());
        assertEquals(AvailabilityStatus.AVAILABLE, entry.getToStatus());
        assertEquals(PRODUCT, entry.getProductId());
        var availability = initial();
        assertThrows(NullPointerException.class, () -> ProductAvailabilityHistory.changed(null, availability));
    }
}
