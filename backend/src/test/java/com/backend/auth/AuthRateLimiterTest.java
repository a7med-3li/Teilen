package com.backend.auth;

import com.backend.auth.security.AuthRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthRateLimiterTest {

    @Test
    void rateLimiterBlocksAfterFiveFailures() {
        AuthRateLimiter limiter = new AuthRateLimiter();
        String key = "test-user-ip";

        for (int i = 0; i < 4; i++) {
            assertDoesNotThrow(() -> limiter.checkAllowed(key));
            limiter.recordFailure(key);
        }

        limiter.recordFailure(key);
        assertThrows(ResponseStatusException.class, () -> limiter.checkAllowed(key));

        limiter.recordSuccess(key);
        assertDoesNotThrow(() -> limiter.checkAllowed(key));
    }
}
