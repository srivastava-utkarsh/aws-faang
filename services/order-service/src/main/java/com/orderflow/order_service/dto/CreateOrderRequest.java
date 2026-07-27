package com.orderflow.order_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateOrderRequest(

        @NotBlank
        String userId,

        @NotEmpty
        @Valid
        List<OrderItemRequest> items
) {
}
