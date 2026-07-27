package com.orderflow.order_service.service;

import com.orderflow.order_service.config.AwsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Claims an idempotency key via a conditional put so that two concurrent
 * requests with the same key can never both "win" — DynamoDB rejects the
 * second write instead of the app racing on a read-then-write check.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private static final String KEY_ATTR = "idempotencyKey";
    private static final String ORDER_ID_ATTR = "orderId";
    private static final String TTL_ATTR = "expiresAt"; // must match dynamodb.yaml TimeToLiveSpecification
    private static final long TTL_DAYS = 1;

    private final DynamoDbClient dynamoDbClient;
    private final AwsProperties awsProperties;

    /**
     * Attempts to claim the key for a new order.
     *
     * @return empty if the key was successfully claimed (caller should proceed),
     *         or the previously stored orderId if this key was already used.
     */
    public Optional<UUID> claim(String idempotencyKey, UUID orderId) {
        long ttl = Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS).getEpochSecond();

        try {
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(awsProperties.dynamodb().idempotencyTable())
                    .item(Map.of(
                            KEY_ATTR, AttributeValue.fromS(idempotencyKey),
                            ORDER_ID_ATTR, AttributeValue.fromS(orderId.toString()),
                            TTL_ATTR, AttributeValue.fromN(String.valueOf(ttl))))
                    .conditionExpression("attribute_not_exists(" + KEY_ATTR + ")")
                    .build());
            return Optional.empty();
        } catch (ConditionalCheckFailedException e) {
            return Optional.of(fetchExistingOrderId(idempotencyKey));
        }
    }

    private UUID fetchExistingOrderId(String idempotencyKey) {
        GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(awsProperties.dynamodb().idempotencyTable())
                .key(Map.of(KEY_ATTR, AttributeValue.fromS(idempotencyKey)))
                .consistentRead(true)
                .build());
        return UUID.fromString(response.item().get(ORDER_ID_ATTR).s());
    }
}
