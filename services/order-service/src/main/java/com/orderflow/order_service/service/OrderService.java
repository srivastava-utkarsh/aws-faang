package com.orderflow.order_service.service;

import com.orderflow.order_service.dto.CreateOrderRequest;
import com.orderflow.order_service.dto.OrderItemRequest;
import com.orderflow.order_service.dto.OrderResponse;
import com.orderflow.order_service.exception.OrderNotFoundException;
import com.orderflow.order_service.messaging.OrderQueuePublisher;
import com.orderflow.order_service.model.Order;
import com.orderflow.order_service.model.OrderItem;
import com.orderflow.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final IdempotencyService idempotencyService;
    private final OrderQueuePublisher orderQueuePublisher;

    @Transactional
    public OrderResponse createOrder(String idempotencyKey, CreateOrderRequest request) {
        Order order = toOrder(request);

        // Claim the key before writing to RDS: if this fails, no order row is ever
        // created for the duplicate request, so there's nothing to clean up.
        Optional<UUID> existingOrderId = idempotencyService.claim(idempotencyKey, order.getId());
        if (existingOrderId.isPresent()) {
            log.info("Duplicate request for idempotency key {}, returning existing order {}",
                    idempotencyKey, existingOrderId.get());
            return getOrder(existingOrderId.get());
        }
        log.info("Creating fresh Order for idempotency key {},  New order {}", idempotencyKey, order.getId());
        orderRepository.save(order);
        orderQueuePublisher.publish(order);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return OrderResponse.from(order);
    }

    private Order toOrder(CreateOrderRequest request) {
        BigDecimal total = request.items().stream()
                .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Order order = new Order(request.userId(), total);
        for (OrderItemRequest item : request.items()) {
            order.addItem(new OrderItem(item.productId(), item.quantity(), item.unitPrice()));
        }
        return order;
    }
}
