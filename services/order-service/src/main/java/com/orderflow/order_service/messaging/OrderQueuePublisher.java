package com.orderflow.order_service.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order_service.config.AwsProperties;
import com.orderflow.order_service.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderQueuePublisher {

    private final SqsClient sqsClient;
    private final AwsProperties awsProperties;
    // Constructed directly, not injected: Spring Boot 4's own JacksonAutoConfiguration
    // now wires up Jackson 3's tools.jackson.databind.ObjectMapper, not this classic
    // com.fasterxml.jackson.databind one — there's no Spring-managed bean of this type
    // to inject. jackson-databind (2.x) is only on the classpath as an explicit
    // dependency for this one direct use, not because Spring provides it.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void publish(Order order) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("orderId", order.getId().toString());
        message.put("userId", order.getUserId());
        message.put("totalAmount", order.getTotalAmount());
        message.put("timestamp", Instant.now().toString());
        message.put("items", order.getItems().stream()
                .map(i -> Map.of(
                        "productId", i.getProductId(),
                        "quantity", i.getQuantity(),
                        "unitPrice", i.getUnitPrice()))
                .collect(Collectors.toList()));

        String body = writeJson(message);

        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(awsProperties.sqs().orderQueueUrl())
                .messageBody(body)
                .build());

        log.info("Published order {} to SQS order queue", order.getId());
    }

    private String writeJson(Map<String, Object> message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize order message", e);
        }
    }
}
