package com.orderflow.order_service.dto;

import com.orderflow.order_service.model.Order;
import com.orderflow.order_service.model.OrderStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String userId,
        OrderStatus status,
        BigDecimal totalAmount,
        OffsetDateTime createdAt,
        List<OrderItemResponse> items
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getItems().stream()
                        .map(i -> new OrderItemResponse(i.getProductId(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal()))
                        .toList()
        );
    }
}
