package com.orderflow.inventory.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "stock")
@Getter
@Setter
@NoArgsConstructor
public class Stock {

    @Id
    @Column(name = "product_id")
    private String productId;

    @Column(name = "available_qty", nullable = false)
    private Integer availableQty;

    @Column(name = "reserved_qty", nullable = false)
    private Integer reservedQty;

    /**
     * Hibernate manages this automatically: it is read on load, compared in
     * the UPDATE's WHERE clause, and incremented on write. Concurrent
     * reservations for the same product therefore cannot both succeed —
     * the loser gets an OptimisticLockingFailureException.
     */
    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false, insertable = false)
    private OffsetDateTime updatedAt;

    public boolean hasAtLeast(int quantity) {
        return availableQty >= quantity;
    }

    public void reserve(int quantity) {
        this.availableQty -= quantity;
        this.reservedQty += quantity;
    }
}
