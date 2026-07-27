package com.orderflow.authorizer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * API Gateway REQUEST authorizer for every /orders call. Runs before the
 * Order Service ever sees the request — a bad token is rejected at the
 * edge, not inside a running ECS task.
 */
public class JwtAuthorizerHandler implements RequestHandler<APIGatewayCustomAuthorizerEvent, Map<String, Object>> {

    private final SecretKey signingKey;

    /** No-arg constructor is what Lambda actually instantiates in AWS. */
    public JwtAuthorizerHandler() {
        this(System.getenv("JWT_SECRET"));
    }

    /** Package-private: lets tests inject a known secret instead of reading the environment. */
    JwtAuthorizerHandler(String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Map<String, Object> handleRequest(APIGatewayCustomAuthorizerEvent event, Context context) {
        String token = extractToken(event);

        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setSigningKey(signingKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException | IllegalArgumentException e) {
            // The literal message "Unauthorized" is what makes a REST API
            // custom authorizer failure map to HTTP 401. Any other message
            // (or an uncaught exception) becomes a 500 instead.
            throw new RuntimeException("Unauthorized");
        }

        String userId = claims.getSubject();
        if (userId == null || userId.isBlank()) {
            throw new RuntimeException("Unauthorized");
        }

        return PolicyBuilder.allow(userId, event.getMethodArn());
    }

    private String extractToken(APIGatewayCustomAuthorizerEvent event) {
        Map<String, String> headers = event.getHeaders();
        String header = headers != null ? headers.get("Authorization") : null;
        if (header == null || !header.startsWith("Bearer ")) {
            throw new RuntimeException("Unauthorized");
        }
        return header.substring("Bearer ".length());
    }
}
