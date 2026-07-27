package com.orderflow.order_service.exception;

public class MissingIdempotencyKeyException extends RuntimeException {

    public MissingIdempotencyKeyException() {
        super("X-Idempotency-Key header is required");
    }
}
