package com.orderflow.authorizer;

import com.amazonaws.services.lambda.runtime.events.APIGatewayCustomAuthorizerEvent;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JwtAuthorizerHandlerTest {

    private static final String SECRET = "test-secret-at-least-32-bytes-long!!";
    private final JwtAuthorizerHandler handler = new JwtAuthorizerHandler(SECRET);

    @Test
    void validTokenReturnsAllowPolicyWithUserId() {
        String token = signedToken("user-123", new Date(System.currentTimeMillis() + 60_000));
        APIGatewayCustomAuthorizerEvent event = eventWithToken(token);

        Map<String, Object> result = handler.handleRequest(event, null);

        assertEquals("user-123", result.get("principalId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> policyDoc = (Map<String, Object>) result.get("policyDocument");
        assertNotNull(policyDoc);
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) result.get("context");
        assertEquals("user-123", context.get("userId"));
    }

    @Test
    void tokenMintedByGenerateTestJwtShellScriptIsAccepted() throws Exception {
        // Proves scripts/generate-test-jwt.sh (plain openssl, no jjwt) produces
        // a token this handler's jjwt-based parser actually accepts — the two
        // token-minting paths must agree since the shell script is what the
        // testing guide tells you to run against a real deployment. Runs the
        // real script (fresh expiry each time) rather than a hardcoded token,
        // so this can't rot into a flaky "expired token" failure.
        Path scriptPath = Paths.get("").toAbsolutePath()
                .resolve("../../scripts/generate-test-jwt.sh").normalize();
        assumeTrue(Files.isExecutable(scriptPath), "generate-test-jwt.sh not found/executable at " + scriptPath);

        ProcessBuilder pb = new ProcessBuilder(scriptPath.toString(), "user-123");
        pb.environment().put("JWT_SECRET", SECRET);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String token = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), "generate-test-jwt.sh exited non-zero, output: " + token);

        APIGatewayCustomAuthorizerEvent event = eventWithToken(token);
        Map<String, Object> result = handler.handleRequest(event, null);
        assertEquals("user-123", result.get("principalId"));
    }

    @Test
    void expiredTokenThrowsUnauthorized() {
        String token = signedToken("user-123", new Date(System.currentTimeMillis() - 60_000));
        APIGatewayCustomAuthorizerEvent event = eventWithToken(token);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.handleRequest(event, null));
        assertEquals("Unauthorized", ex.getMessage());
    }

    @Test
    void wrongSecretThrowsUnauthorized() {
        SecretKey otherKey = Keys.hmacShaKeyFor("a-completely-different-secret-key!!".getBytes());
        String token = Jwts.builder()
                .setSubject("user-123")
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(otherKey)
                .compact();
        APIGatewayCustomAuthorizerEvent event = eventWithToken(token);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.handleRequest(event, null));
        assertEquals("Unauthorized", ex.getMessage());
    }

    @Test
    void missingAuthorizationHeaderThrowsUnauthorized() {
        APIGatewayCustomAuthorizerEvent event = APIGatewayCustomAuthorizerEvent.builder()
                .withHeaders(Map.of())
                .withMethodArn("arn:aws:execute-api:ap-south-1:123456789012:abc123/dev/POST/orders")
                .build();

        RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.handleRequest(event, null));
        assertEquals("Unauthorized", ex.getMessage());
    }

    private String signedToken(String subject, Date expiry) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes());
        return Jwts.builder()
                .setSubject(subject)
                .setExpiration(expiry)
                .signWith(key)
                .compact();
    }

    private APIGatewayCustomAuthorizerEvent eventWithToken(String token) {
        return APIGatewayCustomAuthorizerEvent.builder()
                .withHeaders(Map.of("Authorization", "Bearer " + token))
                .withMethodArn("arn:aws:execute-api:ap-south-1:123456789012:abc123/dev/POST/orders")
                .build();
    }
}
