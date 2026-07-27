package com.orderflow.order_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orderflow.aws")
public record AwsProperties(
        String region,
        Sqs sqs,
        Dynamodb dynamodb
) {
    public record Sqs(String orderQueueUrl) {
    }

    public record Dynamodb(String idempotencyTable) {
    }
}
