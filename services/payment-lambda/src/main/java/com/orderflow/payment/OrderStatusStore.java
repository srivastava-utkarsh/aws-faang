package com.orderflow.payment;

import java.util.UUID;

/**
 * Narrow seam over the orders table so the handler's decision logic can be
 * tested without a live database.
 */
public interface OrderStatusStore {

    /**
     * Moves an order out of PENDING, but only if it is still PENDING.
     *
     * @return number of rows updated — 0 means the order was already in a
     *         terminal state (a duplicate delivery) or does not exist.
     */
    int updateStatusIfPending(UUID orderId, String newStatus);
}
