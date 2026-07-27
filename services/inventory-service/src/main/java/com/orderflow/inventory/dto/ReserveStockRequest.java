package com.orderflow.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ReserveStockRequest(

        /** Doubles as the idempotency key — one reservation per order, ever. */
        @NotNull
        UUID orderId,

        @NotEmpty
        @Valid
        List<ReserveItemRequest> items
) {
}
