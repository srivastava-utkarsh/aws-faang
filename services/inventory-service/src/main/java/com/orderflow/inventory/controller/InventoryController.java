package com.orderflow.inventory.controller;

import com.orderflow.inventory.dto.ReserveStockRequest;
import com.orderflow.inventory.dto.ReserveStockResponse;
import com.orderflow.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal API — reachable only from the order-processor Lambda, via the
 * internal ALB. There is no authorizer in front of this: the network path
 * itself is the access control (see the SG chain in vpc.yaml). Nothing on
 * the public internet can route to it.
 */
@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @PostMapping("/reserve")
    public ResponseEntity<ReserveStockResponse> reserve(@Valid @RequestBody ReserveStockRequest request) {
        return ResponseEntity.ok(inventoryService.reserve(request));
    }
}
