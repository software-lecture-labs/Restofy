package com.restofy.order.domain.cancellation;

import com.restofy.order.domain.cancellation.CancellationPolicy.Mode;
import com.restofy.order.domain.cancellation.CancellationPolicy.Requester;
import com.restofy.order.domain.enums.FulfillmentStatus;
import com.restofy.order.domain.enums.PaymentStatus;
import com.restofy.order.domain.exception.BusinessException;
import com.restofy.order.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CancellationPolicyTests {

    @Test
    void anOrderThatIsOnlyReceivedIsCancelledImmediatelyByAnyoneAllowedToAsk() {
        for (Requester requester : Requester.values()) {
            for (PaymentStatus payment : new PaymentStatus[]{PaymentStatus.NOT_REQUESTED, PaymentStatus.PENDING,
                    PaymentStatus.FAILED}) {
                assertEquals(Mode.IMMEDIATE, CancellationPolicy.evaluate(requester, FulfillmentStatus.RECEIVED, payment));
            }
        }
    }

    @Test
    void anOrderInPreparationNeedsTheKitchenToConfirm() {
        for (Requester requester : Requester.values()) {
            assertEquals(Mode.NEEDS_KITCHEN_CONFIRMATION,
                    CancellationPolicy.evaluate(requester, FulfillmentStatus.PREPARING, PaymentStatus.NOT_REQUESTED));
        }
    }

    @Test
    void readyServedAndCompletedOrdersCannotBeCancelledByAnyone() {
        for (Requester requester : Requester.values()) {
            for (FulfillmentStatus status : new FulfillmentStatus[]{FulfillmentStatus.READY, FulfillmentStatus.SERVED,
                    FulfillmentStatus.COMPLETED}) {
                BusinessException refused = assertThrows(BusinessException.class,
                        () -> CancellationPolicy.evaluate(requester, status, PaymentStatus.NOT_REQUESTED));
                assertEquals(ErrorCode.CANCELLATION_NOT_ALLOWED, refused.code());
                assertEquals("ORDER_ALREADY_PREPARED", refused.details().get("reason"));
            }
        }
    }

    @Test
    void anAlreadyCancelledOrderIsReportedAsSuch() {
        BusinessException refused = assertThrows(BusinessException.class, () -> CancellationPolicy.evaluate(
                Requester.WAITER, FulfillmentStatus.CANCELLED, PaymentStatus.CANCELLED));
        assertEquals(ErrorCode.ORDER_ALREADY_CANCELLED, refused.code());
    }

    @Test
    void aCustomerCannotCancelAPaidOrderButAWaiterOrAdminCan() {
        for (FulfillmentStatus status : new FulfillmentStatus[]{FulfillmentStatus.RECEIVED, FulfillmentStatus.PREPARING}) {
            BusinessException refused = assertThrows(BusinessException.class,
                    () -> CancellationPolicy.evaluate(Requester.GUEST, status, PaymentStatus.PAID));
            assertEquals("ORDER_ALREADY_PAID", refused.details().get("reason"));
            for (Requester staff : new Requester[]{Requester.WAITER, Requester.ADMIN}) {
                assertEquals(status == FulfillmentStatus.RECEIVED ? Mode.IMMEDIATE : Mode.NEEDS_KITCHEN_CONFIRMATION,
                        CancellationPolicy.evaluate(staff, status, PaymentStatus.PAID));
            }
        }
    }
}
