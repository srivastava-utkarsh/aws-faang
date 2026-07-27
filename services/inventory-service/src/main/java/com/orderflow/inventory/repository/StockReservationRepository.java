package com.orderflow.inventory.repository;

import com.orderflow.inventory.model.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface StockReservationRepository extends JpaRepository<StockReservation, UUID> {
}
