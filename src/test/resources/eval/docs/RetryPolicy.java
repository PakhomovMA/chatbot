package com.shop.payments;

import java.time.Duration;
import java.util.Set;

public final class RetryPolicy {
    public static final int MAX_ATTEMPTS = 7;
    public static final Duration INITIAL_BACKOFF = Duration.ofMillis(125);
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(9);
    public static final double JITTER_RATIO = 0.20;
    public static final Set<String> RETRYABLE_CODES = Set.of("ACQUIRER_BUSY", "NETWORK_TIMEOUT");

    public Duration delayFor(int attempt) {
        if (attempt < 1 || attempt > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("attempt outside retry policy");
        }
        long multiplier = 1L << Math.min(attempt - 1, 6);
        long millis = Math.min(INITIAL_BACKOFF.toMillis() * multiplier, MAX_BACKOFF.toMillis());
        return Duration.ofMillis(millis);
    }

    public boolean shouldRetry(String code, boolean idempotencyKeyPresent) {
        return idempotencyKeyPresent && RETRYABLE_CODES.contains(code);
    }

    public String auditTag(int attempt) {
        return "retry-" + attempt + "-v3";
    }
}
