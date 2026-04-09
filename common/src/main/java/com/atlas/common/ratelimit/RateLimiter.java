package com.atlas.common.ratelimit;

import java.util.UUID;

/**
 * Abstraction over the rate-limiting algorithm.
 * Returns {@code true} when the request is allowed, {@code false} when the bucket is empty.
 */
public interface RateLimiter {

    /**
     * Attempt to consume one token for the given tenant.
     *
     * @param tenantId the tenant whose bucket to check
     * @param requestsPerMinute the refill rate for this tenant
     * @param burstSize the maximum token accumulation
     * @return {@code true} if a token was consumed (request allowed), {@code false} otherwise
     */
    boolean tryConsume(UUID tenantId, int requestsPerMinute, int burstSize);
}
