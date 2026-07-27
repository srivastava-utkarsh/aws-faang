package com.orderflow.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ReserveItemRequest(

        @NotBlank
        String productId,

        @NotNull
        @Positive
        Integer quantity
) {
}
