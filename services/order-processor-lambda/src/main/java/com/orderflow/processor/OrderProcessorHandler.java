package com.orderflow.processor;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Consumes the order queue and drives the async half of the order flow:
 * reserve stock, then publish OrderConfirmed (or OrderFailed) so the payment
 * and notification consumers can pick it up.
 */
public class OrderProcessorHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final InventoryClient inventoryClient;
    private final OrderEventPublisher eventPublisher;

    /** Constructor Lambda actually invokes in AWS. */
    public OrderProcessorHandler() {
        this(new InventoryClient(System.getenv("INVENTORY_SERVICE_URL")),
                new OrderEventPublisher(System.getenv("EVENT_BUS_NAME")));
    }

    OrderProcessorHandler(InventoryClient inventoryClient, OrderEventPublisher eventPublisher) {
        this.inventoryClient = inventoryClient;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            try {
                process(message);
            } catch (Exception e) {
                // Report ONLY this message as failed. Without partial batch
                // failure reporting, one bad message forces redelivery of
                // every message in the batch — including the ones that
                // already succeeded, which would then be processed twice.
                // Requires FunctionResponseTypes: [ReportBatchItemFailures]
                // on the event source mapping to take effect.
                log(context, "Failed to process message " + message.getMessageId() + ": " + e);
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(message.getMessageId())
                        .build());
            }
        }

        return SQSBatchResponse.builder().withBatchItemFailures(failures).build();
    }

    private void process(SQSEvent.SQSMessage message) throws Exception {
        JsonNode order = MAPPER.readTree(message.getBody());
        String orderId = order.get("orderId").asText();

        boolean reserved = inventoryClient.reserve(orderId, order.get("items"));

        if (reserved) {
            eventPublisher.publishOrderConfirmed(order);
        } else {
            // A business rejection is a SUCCESSFUL processing outcome: we
            // publish OrderFailed and do NOT rethrow, so the message is
            // deleted from the queue rather than retried into the DLQ.
            eventPublisher.publishOrderFailed(order, "INSUFFICIENT_STOCK");
        }
    }

    private void log(Context context, String message) {
        if (context != null && context.getLogger() != null) {
            context.getLogger().log(message);
        }
    }
}
