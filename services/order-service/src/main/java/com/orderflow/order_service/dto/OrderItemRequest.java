package com.orderflow.order_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record OrderItemRequest(

        @NotBlank
        String productId,

        @NotNull
        @Positive
        Integer quantity,

        @NotNull
        @Positive
        BigDecimal unitPrice
) {
}
