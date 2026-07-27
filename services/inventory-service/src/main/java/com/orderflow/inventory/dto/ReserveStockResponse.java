package com.orderflow.inventory.dto;

import java.util.UUID;

public record ReserveStockResponse(
        UUID orderId,
        ReservationStatus status
) {
    public enum ReservationStatus {
        /** Stock was decremented by this call. */
        RESERVED,
        /** This order was already reserved by an earlier call — no double-decrement. */
        ALREADY_RESERVED
    }

    public static ReserveStockResponse reserved(UUID orderId) {
        return new ReserveStockResponse(orderId, ReservationStatus.RESERVED);
    }

    public static ReserveStockResponse alreadyReserved(UUID orderId) {
        return new ReserveStockResponse(orderId, ReservationStatus.ALREADY_RESERVED);
    }
}
