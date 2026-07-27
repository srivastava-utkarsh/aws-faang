package com.orderflow.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentHandlerTest {

    private static final String ORDER_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void chargeableOrderIsMarkedPaid() {
        RecordingStore store = new RecordingStore(1);
        PaymentHandler handler = new PaymentHandler(store, new BigDecimal("10000"));

        String result = handler.handleRequest(eventWithAmount(99.98), null);

        assertEquals("PAID", result);
        assertEquals(List.of(UUID.fromString(ORDER_ID) + ":PAID"), store.calls);
    }

    @Test
    void orderAboveDeclineThresholdIsMarkedPaymentFailed() {
        RecordingStore store = new RecordingStore(1);
        PaymentHandler handler = new PaymentHandler(store, new BigDecimal("10000"));

        String result = handler.handleRequest(eventWithAmount(25000), null);

        assertEquals("PAYMENT_FAILED", result);
        assertEquals(List.of(UUID.fromString(ORDER_ID) + ":PAYMENT_FAILED"), store.calls);
    }

    @Test
    void duplicateDeliveryIsSkippedRatherThanReapplied() {
        // 0 rows updated = the conditional UPDATE found the order already in
        // a terminal state. EventBridge retries async invocations, so this
        // path is expected traffic, not an error.
        RecordingStore store = new RecordingStore(0);
        PaymentHandler handler = new PaymentHandler(store, new BigDecimal("10000"));

        String result = handler.handleRequest(eventWithAmount(99.98), null);

        assertEquals("SKIPPED", result);
    }

    @Test
    void integerAmountParsesCorrectly() {
        // Lambda deserialises 100 as Integer but 99.98 as Double — both must work.
        RecordingStore store = new RecordingStore(1);
        PaymentHandler handler = new PaymentHandler(store, new BigDecimal("10000"));

        assertEquals("PAID", handler.handleRequest(eventWithAmount(100), null));
    }

    @Test
    void amountExactlyAtThresholdIsStillCharged() {
        RecordingStore store = new RecordingStore(1);
        PaymentHandler handler = new PaymentHandler(store, new BigDecimal("10000"));

        assertEquals("PAID", handler.handleRequest(eventWithAmount(10000), null));
    }

    @Test
    void eventWithoutDetailIsRejected() {
        PaymentHandler handler = new PaymentHandler(new RecordingStore(1), new BigDecimal("10000"));

        assertThrows(IllegalArgumentException.class,
                () -> handler.handleRequest(Map.of("source", "com.orderflow.order"), null));
    }

    private static Map<String, Object> eventWithAmount(Number amount) {
        return Map.of(
                "source", "com.orderflow.order",
                "detail-type", "OrderConfirmed",
                "detail", Map.of(
                        "orderId", ORDER_ID,
                        "userId", "user-123",
                        "totalAmount", amount));
    }

    private static class RecordingStore implements OrderStatusStore {
        final List<String> calls = new ArrayList<>();
        private final int rowsToReturn;

        RecordingStore(int rowsToReturn) {
            this.rowsToReturn = rowsToReturn;
        }

        @Override
        public int updateStatusIfPending(UUID orderId, String newStatus) {
            calls.add(orderId + ":" + newStatus);
            return rowsToReturn;
        }
    }
}
