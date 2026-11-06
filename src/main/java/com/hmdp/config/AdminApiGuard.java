package com.hmdp.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Protects administrative write endpoints that are not backed by a user-role model. */
@Component
public class AdminApiGuard {
    private final byte[] expected;

    public AdminApiGuard(@Value("${cityai.admin.token:}") String token) {
        expected = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    public void verify(String token) {
        if (expected.length == 0 || token == null
                || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Invalid admin API token");
        }
    }
}
