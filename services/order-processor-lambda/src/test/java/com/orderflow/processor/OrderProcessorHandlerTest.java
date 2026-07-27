package com.orderflow.processor;

import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderProcessorHandlerTest {

    private static final String ORDER_JSON = """
            {"orderId":"11111111-1111-1111-1111-111111111111",
             "userId":"user-123",
             "totalAmount":99.98,
             "timestamp":"2026-01-01T00:00:00Z",
             "items":[{"productId":"prod-001","quantity":2,"unitPrice":49.99}]}
            """;

    @Test
    void reservedOrderPublishesOrderConfirmedAndReportsNoFailures() {
        RecordingPublisher publisher = new RecordingPublisher();
        OrderProcessorHandler handler = new OrderProcessorHandler(alwaysReserves(true), publisher);

        SQSBatchResponse response = handler.handleRequest(eventWith(ORDER_JSON), null);

        assertEquals(List.of("OrderConfirmed"), publisher.published);
        assertTrue(response.getBatchItemFailures().isEmpty());
    }

    @Test
    void outOfStockPublishesOrderFailedAndIsNotRetried() {
        // The important assertion is the empty failure list: "out of stock" is
        // a real answer, not an error. Reporting it as a batch item failure
        // would retry it 3 times and then dump it in the DLQ as if the system
        // were broken.
        RecordingPublisher publisher = new RecordingPublisher();
        OrderProcessorHandler handler = new OrderProcessorHandler(alwaysReserves(false), publisher);

        SQSBatchResponse response = handler.handleRequest(eventWith(ORDER_JSON), null);

        assertEquals(List.of("OrderFailed"), publisher.published);
        assertTrue(response.getBatchItemFailures().isEmpty(), "business rejection must not be retried");
    }

    @Test
    void transientInventoryFailureIsReportedForRetry() {
        RecordingPublisher publisher = new RecordingPublisher();
        InventoryClient failing = new InventoryClient("http://unused") {
            @Override
            public boolean reserve(String orderId, JsonNode items) {
                throw new TransientFailureException("inventory-service 503");
            }
        };
        OrderProcessorHandler handler = new OrderProcessorHandler(failing, publisher);

        SQSBatchResponse response = handler.handleRequest(eventWith(ORDER_JSON), null);

        assertTrue(publisher.published.isEmpty(), "no event should be published when the call failed");
        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("msg-0", response.getBatchItemFailures().get(0).getItemIdentifier());
    }

    @Test
    void oneBadMessageDoesNotFailTheWholeBatch() {
        RecordingPublisher publisher = new RecordingPublisher();
        // Second message is unparseable JSON.
        SQSEvent event = eventWith(ORDER_JSON, "{ not json");
        OrderProcessorHandler handler = new OrderProcessorHandler(alwaysReserves(true), publisher);

        SQSBatchResponse response = handler.handleRequest(event, null);

        assertEquals(List.of("OrderConfirmed"), publisher.published, "good message still processed");
        assertEquals(1, response.getBatchItemFailures().size(), "only the bad message is retried");
        assertEquals("msg-1", response.getBatchItemFailures().get(0).getItemIdentifier());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static InventoryClient alwaysReserves(boolean result) {
        return new InventoryClient("http://unused") {
            @Override
            public boolean reserve(String orderId, JsonNode items) {
                return result;
            }
        };
    }

    private static SQSEvent eventWith(String... bodies) {
        List<SQSEvent.SQSMessage> messages = new ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            SQSEvent.SQSMessage m = new SQSEvent.SQSMessage();
            m.setMessageId("msg-" + i);
            m.setBody(bodies[i]);
            messages.add(m);
        }
        SQSEvent event = new SQSEvent();
        event.setRecords(messages);
        return event;
    }

    private static class RecordingPublisher extends OrderEventPublisher {
        final List<String> published = new ArrayList<>();

        RecordingPublisher() {
            super(null, "test-bus");
        }

        @Override
        public void publishOrderConfirmed(JsonNode orderMessage) {
            published.add("OrderConfirmed");
        }

        @Override
        public void publishOrderFailed(JsonNode orderMessage, String reason) {
            published.add("OrderFailed");
        }
    }
}
