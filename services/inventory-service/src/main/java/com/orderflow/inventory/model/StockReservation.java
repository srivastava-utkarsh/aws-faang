package com.orderflow.inventory.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per order that has had its stock reserved. Existence of the row IS
 * the idempotency check — see InventoryService.reserve.
 *
 * Implements Persistable for the same reason Order does in order-service:
 * the id is app-assigned, so save() would otherwise issue a SELECT first to
 * decide between INSERT and UPDATE.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@NoArgsConstructor
public class StockReservation implements Persistable<UUID> {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "reserved_at", nullable = false, insertable = false)
    private OffsetDateTime reservedAt;

    @Transient
    private boolean isNew = true;

    public StockReservation(UUID orderId) {
        this.orderId = orderId;
    }

    @Override
    public UUID getId() {
        return orderId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
