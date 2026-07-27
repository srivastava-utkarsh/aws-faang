package com.orderflow.authorizer;

import java.util.List;
import java.util.Map;

/**
 * Builds the exact JSON shape API Gateway requires back from a REQUEST
 * authorizer: principalId + an IAM policy document + an optional context
 * map. A Map serializes to this shape via Lambda's default Jackson
 * integration, so no dedicated POJO is needed.
 */
final class PolicyBuilder {

    private PolicyBuilder() {
    }

    static Map<String, Object> allow(String principalId, String methodArn) {
        return Map.of(
                "principalId", principalId,
                "policyDocument", Map.of(
                        "Version", "2012-10-17",
                        "Statement", List.of(Map.of(
                                "Action", "execute-api:Invoke",
                                "Effect", "Allow",
                                "Resource", methodArn))),
                // Forwarded to the backend by api-gateway.yaml's integration request
                // mapping (context.authorizer.userId -> X-User-Id header) — HTTP_PROXY
                // integrations don't forward authorizer context automatically.
                "context", Map.of("userId", principalId)
        );
    }
}
