package com.orderflow.processor;

/**
 * Thrown when the failure is worth retrying — inventory-service was
 * unreachable, timed out, returned 5xx, or lost an optimistic-lock race.
 *
 * <p>The distinction from a business rejection (out of stock) is the whole
 * point: a transient failure must go back on the queue, while "out of
 * stock" must NOT be retried 3 times and then land in the DLQ as if
 * something were broken.
 */
public class TransientFailureException extends RuntimeException {

    public TransientFailureException(String message) {
        super(message);
    }

    public TransientFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
