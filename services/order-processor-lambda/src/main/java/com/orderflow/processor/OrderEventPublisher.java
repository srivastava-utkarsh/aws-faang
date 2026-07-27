package com.orderflow.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;

/**
 * Publishes domain events to the custom EventBridge bus.
 *
 * <p>The {@code source} and {@code detail-type} values here are not free
 * text — they must match the EventPattern in infra/messaging/eventbridge.yaml
 * exactly, or the rules silently fail to match and the event goes nowhere.
 */
public class OrderEventPublisher {

    /** Must equal the `source` in every rule's EventPattern. */
    static final String EVENT_SOURCE = "com.orderflow.order";
    static final String ORDER_CONFIRMED = "OrderConfirmed";
    static final String ORDER_FAILED = "OrderFailed";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventBridgeClient client;
    private final String eventBusName;

    public OrderEventPublisher(String eventBusName) {
        this(EventBridgeClient.create(), eventBusName);
    }

    OrderEventPublisher(EventBridgeClient client, String eventBusName) {
        this.client = client;
        this.eventBusName = eventBusName;
    }

    public void publishOrderConfirmed(JsonNode orderMessage) {
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("orderId", orderMessage.get("orderId").asText());
        detail.put("userId", orderMessage.get("userId").asText());
        detail.set("totalAmount", orderMessage.get("totalAmount"));
        detail.set("items", orderMessage.get("items"));
        publish(ORDER_CONFIRMED, detail);
    }

    public void publishOrderFailed(JsonNode orderMessage, String reason) {
        ObjectNode detail = MAPPER.createObjectNode();
        detail.put("orderId", orderMessage.get("orderId").asText());
        detail.put("userId", orderMessage.get("userId").asText());
        detail.put("reason", reason);
        publish(ORDER_FAILED, detail);
    }

    private void publish(String detailType, ObjectNode detail) {
        PutEventsResponse response = client.putEvents(PutEventsRequest.builder()
                .entries(PutEventsRequestEntry.builder()
                        .eventBusName(eventBusName)
                        .source(EVENT_SOURCE)
                        .detailType(detailType)
                        .detail(detail.toString())
                        .build())
                .build());

        // PutEvents returns HTTP 200 even when individual entries fail —
        // failures show up only in FailedEntryCount. Not checking this is a
        // classic way to lose events silently.
        if (response.failedEntryCount() != null && response.failedEntryCount() > 0) {
            throw new TransientFailureException(
                    "EventBridge rejected %s event: %s".formatted(detailType, response.entries()));
        }
    }
}
