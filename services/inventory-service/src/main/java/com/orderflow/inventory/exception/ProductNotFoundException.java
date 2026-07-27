package com.orderflow.inventory.exception;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String productId) {
        super("Unknown product: " + productId);
    }
}
