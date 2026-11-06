package com.hmdp.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"micro-user", "micro-content", "micro-ai"})
public class InternalApiGuard {
    private final byte[] expected;

    public InternalApiGuard(@Value("${cityai.internal.token}") String token) {
        if (token.isBlank()) {
            throw new IllegalArgumentException("cityai.internal.token must not be blank");
        }
        expected = token.getBytes(StandardCharsets.UTF_8);
    }

    public void verify(String token) {
        if (token == null || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Invalid internal service token");
        }
    }
}
