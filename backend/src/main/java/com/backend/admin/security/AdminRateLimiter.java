package com.backend.admin.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AdminRateLimiter {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long WINDOW_MS = 5 * 60_000L; // 5 minutes

    private static class AttemptRecord {
        int count;
        long windowStart;

        AttemptRecord(long now) {
            this.count = 1;
            this.windowStart = now;
        }
    }

    private final Map<String, AttemptRecord> failedAttempts = new ConcurrentHashMap<>();

    public void checkAllowed(String key) {
        long now = System.currentTimeMillis();
        AttemptRecord record = failedAttempts.get(key);
        if (record != null) {
            synchronized (record) {
                if (now - record.windowStart < WINDOW_MS) {
                    if (record.count >= MAX_FAILED_ATTEMPTS) {
                        long waitSec = (WINDOW_MS - (now - record.windowStart)) / 1000;
                        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "Too many failed attempts. Please wait " + Math.max(1, waitSec) + " seconds.");
                    }
                } else {
                    failedAttempts.remove(key);
                }
            }
        }
    }

    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        failedAttempts.compute(key, (k, existing) -> {
            if (existing == null || (now - existing.windowStart) >= WINDOW_MS) {
                return new AttemptRecord(now);
            }
            synchronized (existing) {
                existing.count++;
                return existing;
            }
        });
    }

    public void recordSuccess(String key) {
        failedAttempts.remove(key);
    }
}
