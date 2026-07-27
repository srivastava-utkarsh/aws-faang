package com.orderflow.inventory.service;

import com.orderflow.inventory.dto.ReserveItemRequest;
import com.orderflow.inventory.dto.ReserveStockRequest;
import com.orderflow.inventory.dto.ReserveStockResponse;
import com.orderflow.inventory.exception.InsufficientStockException;
import com.orderflow.inventory.exception.ProductNotFoundException;
import com.orderflow.inventory.model.Stock;
import com.orderflow.inventory.model.StockReservation;
import com.orderflow.inventory.repository.StockRepository;
import com.orderflow.inventory.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final StockRepository stockRepository;
    private final StockReservationRepository reservationRepository;

    /**
     * Reserves stock for every item in an order, atomically.
     *
     * <p>The whole method is one transaction: if the last item is out of
     * stock, the decrements already applied to earlier items roll back. A
     * partially-reserved order is never a state this can produce.
     */
    @Transactional
    public ReserveStockResponse reserve(ReserveStockRequest request) {
        // Idempotency: SQS is at-least-once, so the order-processor Lambda can
        // legitimately deliver the same order twice. Without this check a
        // redelivery would decrement stock a second time for one order.
        if (reservationRepository.existsById(request.orderId())) {
            log.info("Order {} already reserved — returning without re-reserving", request.orderId());
            return ReserveStockResponse.alreadyReserved(request.orderId());
        }

        for (ReserveItemRequest item : request.items()) {
            Stock stock = stockRepository.findById(item.productId())
                    .orElseThrow(() -> new ProductNotFoundException(item.productId()));

            if (!stock.hasAtLeast(item.quantity())) {
                throw new InsufficientStockException(
                        item.productId(), item.quantity(), stock.getAvailableQty());
            }

            stock.reserve(item.quantity());
        }

        // If two requests for the SAME orderId race past the existsById check
        // above, this insert violates the primary key and the transaction
        // rolls back. That is the desired outcome: the caller (Lambda) fails,
        // SQS redelivers, and the retry sees existsById == true and succeeds.
        // The @Version column on Stock covers the other race — two DIFFERENT
        // orders competing for the same product — by failing the slower UPDATE.
        reservationRepository.save(new StockReservation(request.orderId()));

        log.info("Reserved stock for order {} ({} item(s))", request.orderId(), request.items().size());
        return ReserveStockResponse.reserved(request.orderId());
    }
}
