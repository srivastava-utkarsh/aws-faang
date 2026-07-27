package com.orderflow.inventory;

import com.orderflow.inventory.dto.ReserveItemRequest;
import com.orderflow.inventory.dto.ReserveStockRequest;
import com.orderflow.inventory.dto.ReserveStockResponse;
import com.orderflow.inventory.exception.InsufficientStockException;
import com.orderflow.inventory.exception.ProductNotFoundException;
import com.orderflow.inventory.model.Stock;
import com.orderflow.inventory.model.StockReservation;
import com.orderflow.inventory.repository.StockRepository;
import com.orderflow.inventory.repository.StockReservationRepository;
import com.orderflow.inventory.service.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mockito mocks wired to back onto real maps, so these tests assert on the
 * resulting stock levels (did we actually decrement, and exactly once?)
 * rather than on which methods got called.
 */
class InventoryServiceTest {

    private Map<String, Stock> stockTable;
    private Map<UUID, StockReservation> reservationTable;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        stockTable = new HashMap<>();
        reservationTable = new HashMap<>();
        stockTable.put("prod-001", stock("prod-001", 10));
        stockTable.put("prod-002", stock("prod-002", 3));

        StockRepository stockRepository = mock(StockRepository.class);
        when(stockRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(stockTable.get(inv.<String>getArgument(0))));

        StockReservationRepository reservationRepository = mock(StockReservationRepository.class);
        when(reservationRepository.existsById(any(UUID.class)))
                .thenAnswer(inv -> reservationTable.containsKey(inv.<UUID>getArgument(0)));
        when(reservationRepository.save(any(StockReservation.class))).thenAnswer(inv -> {
            StockReservation r = inv.getArgument(0);
            reservationTable.put(r.getOrderId(), r);
            return r;
        });

        service = new InventoryService(stockRepository, reservationRepository);
    }

    @Test
    void reservesStockAndRecordsReservation() {
        UUID orderId = UUID.randomUUID();

        ReserveStockResponse response = service.reserve(
                new ReserveStockRequest(orderId, List.of(new ReserveItemRequest("prod-001", 4))));

        assertEquals(ReserveStockResponse.ReservationStatus.RESERVED, response.status());
        assertEquals(6, stockTable.get("prod-001").getAvailableQty());
        assertEquals(4, stockTable.get("prod-001").getReservedQty());
        assertTrue(reservationTable.containsKey(orderId));
    }

    @Test
    void redeliveryOfSameOrderDoesNotDecrementStockTwice() {
        UUID orderId = UUID.randomUUID();
        ReserveStockRequest request =
                new ReserveStockRequest(orderId, List.of(new ReserveItemRequest("prod-001", 4)));

        service.reserve(request);
        ReserveStockResponse second = service.reserve(request); // SQS at-least-once redelivery

        assertEquals(ReserveStockResponse.ReservationStatus.ALREADY_RESERVED, second.status());
        assertEquals(6, stockTable.get("prod-001").getAvailableQty(), "stock must only be decremented once");
    }

    @Test
    void insufficientStockThrows() {
        UUID orderId = UUID.randomUUID();

        assertThrows(InsufficientStockException.class, () -> service.reserve(
                new ReserveStockRequest(orderId, List.of(new ReserveItemRequest("prod-002", 99)))));

        assertFalse(reservationTable.containsKey(orderId), "no reservation should be recorded on failure");
    }

    @Test
    void unknownProductThrows() {
        assertThrows(ProductNotFoundException.class, () -> service.reserve(
                new ReserveStockRequest(UUID.randomUUID(), List.of(new ReserveItemRequest("nope", 1)))));
    }

    @Test
    void multiItemOrderReservesEveryItem() {
        UUID orderId = UUID.randomUUID();

        service.reserve(new ReserveStockRequest(orderId, List.of(
                new ReserveItemRequest("prod-001", 2),
                new ReserveItemRequest("prod-002", 1))));

        assertEquals(8, stockTable.get("prod-001").getAvailableQty());
        assertEquals(2, stockTable.get("prod-002").getAvailableQty());
    }

    private static Stock stock(String productId, int available) {
        Stock s = new Stock();
        s.setProductId(productId);
        s.setAvailableQty(available);
        s.setReservedQty(0);
        return s;
    }
}
