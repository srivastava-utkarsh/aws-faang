package com.orderflow.payment;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Triggered by EventBridge on OrderConfirmed. Simulates charging the card,
 * then moves the order to PAID or PAYMENT_FAILED.
 *
 * <p>The input is the full EventBridge envelope
 * ({@code {version, id, detail-type, source, detail: {...}}}), taken as a
 * Map rather than a typed event class — the SDK's concrete event types
 * describe AWS-generated events, and this bus carries our own custom shape.
 */
public class PaymentHandler implements RequestHandler<Map<String, Object>, String> {

    static final String STATUS_PAID = "PAID";
    static final String STATUS_FAILED = "PAYMENT_FAILED";

    private final OrderStatusStore statusStore;
    private final BigDecimal declineAbove;

    /** Constructor Lambda invokes in AWS. */
    public PaymentHandler() {
        this(new JdbcOrderStatusStore(
                        System.getenv("DB_URL"),
                        System.getenv("DB_USERNAME"),
                        System.getenv("DB_PASSWORD")),
                new BigDecimal(orDefault(System.getenv("PAYMENT_DECLINE_ABOVE"), "10000")));
    }

    PaymentHandler(OrderStatusStore statusStore, BigDecimal declineAbove) {
        this.statusStore = statusStore;
        this.declineAbove = declineAbove;
    }

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) event.get("detail");
        if (detail == null) {
            throw new IllegalArgumentException("Event has no 'detail' — not an OrderConfirmed event?");
        }

        UUID orderId = UUID.fromString((String) detail.get("orderId"));
        BigDecimal amount = toBigDecimal(detail.get("totalAmount"));

        String newStatus = charge(amount) ? STATUS_PAID : STATUS_FAILED;
        int rowsUpdated = statusStore.updateStatusIfPending(orderId, newStatus);

        if (rowsUpdated == 0) {
            // Already terminal — this is a duplicate delivery, which is
            // expected behaviour for an async EventBridge target, not an error.
            log(context, "Order " + orderId + " was not PENDING; skipping (duplicate delivery)");
            return "SKIPPED";
        }

        log(context, "Order " + orderId + " -> " + newStatus);
        return newStatus;
    }

    /**
     * Stands in for a real payment gateway. Deterministic on purpose: a
     * random failure would make the DLQ/retry behaviour impossible to
     * demonstrate on demand. Order above PAYMENT_DECLINE_ABOVE is declined,
     * so the failure path can be exercised by placing a large order.
     */
    private boolean charge(BigDecimal amount) {
        return amount.compareTo(declineAbove) <= 0;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Event detail has no totalAmount");
        }
        // Jackson hands numeric JSON to Lambda as Integer/Double/BigDecimal
        // depending on the literal, so normalise via toString rather than
        // casting to any one of them.
        return new BigDecimal(value.toString());
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private void log(Context context, String message) {
        if (context != null && context.getLogger() != null) {
            context.getLogger().log(message);
        }
    }
}
