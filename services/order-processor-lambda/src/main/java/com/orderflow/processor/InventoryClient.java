package com.orderflow.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls inventory-service over the internal ALB. Plain JDK HttpClient — no
 * third-party HTTP library to keep on the classpath.
 */
public class InventoryClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    public InventoryClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build());
    }

    InventoryClient(String baseUrl, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = httpClient;
    }

    /**
     * @return {@code true} if stock is reserved (or was already reserved by a
     *         previous delivery of the same order), {@code false} if the order
     *         is definitively rejected — out of stock or unknown product.
     * @throws TransientFailureException if the call should be retried
     */
    public boolean reserve(String orderId, JsonNode items) {
        String body = buildRequestBody(orderId, items);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/inventory/reserve"))
                .header("Content-Type", "application/json")
                // Below the Lambda's own timeout so a hung inventory-service
                // surfaces as a clean retryable error rather than the whole
                // invocation being killed mid-flight.
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new TransientFailureException("inventory-service unreachable for order " + orderId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientFailureException("interrupted calling inventory-service for order " + orderId, e);
        }

        int status = response.statusCode();

        // 200 covers both RESERVED and ALREADY_RESERVED — inventory-service
        // treats a redelivery as success, so both mean "proceed".
        if (status == 200) {
            return true;
        }
        // Definitive business rejections. Retrying cannot change the answer.
        if (status == 404 || status == 409) {
            return false;
        }
        // 503 (optimistic lock race) and every 5xx are worth another attempt.
        throw new TransientFailureException(
                "inventory-service returned %d for order %s: %s".formatted(status, orderId, response.body()));
    }

    private String buildRequestBody(String orderId, JsonNode items) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("orderId", orderId);
        ArrayNode itemsArray = root.putArray("items");
        for (JsonNode item : items) {
            ObjectNode out = itemsArray.addObject();
            out.put("productId", item.get("productId").asText());
            out.put("quantity", item.get("quantity").asInt());
        }
        return root.toString();
    }
}
