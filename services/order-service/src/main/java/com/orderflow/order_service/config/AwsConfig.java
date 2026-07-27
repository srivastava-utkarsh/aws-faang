package com.orderflow.order_service.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsConfig {

    @Bean
    public SqsClient sqsClient(AwsProperties props) {
        return SqsClient.builder()
                .region(Region.of(props.region()))
                .build();
    }

    @Bean
    public DynamoDbClient dynamoDbClient(AwsProperties props) {
        return DynamoDbClient.builder()
                .region(Region.of(props.region()))
                .build();
    }
}
