package com.atlas.common.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class RedisRateLimiter implements RateLimiter {

    // Atomic token bucket Lua script.
    // KEYS[1] = rate limit key (e.g. "rate_limit:{tenantId}")
    // ARGV[1] = requests_per_minute
    // ARGV[2] = burst_size
    // ARGV[3] = current epoch seconds
    // Returns 1 if allowed, 0 if denied.
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local rate = tonumber(ARGV[1])
            local burst = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local stored = redis.call('GET', key)
            local tokens
            local last_refill

            if stored == false then
                tokens = burst
                last_refill = now
            else
                local parts = {}
                for part in string.gmatch(stored, '([^:]+)') do
                    table.insert(parts, part)
                end
                tokens = tonumber(parts[1])
                last_refill = tonumber(parts[2])
            end

            local elapsed = now - last_refill
            local refill = elapsed * (rate / 60.0)
            tokens = math.min(burst, tokens + refill)
            last_refill = now

            if tokens >= 1.0 then
                tokens = tokens - 1.0
                redis.call('SET', key, string.format('%.6f:%d', tokens, last_refill), 'EX', 120)
                return 1
            else
                redis.call('SET', key, string.format('%.6f:%d', tokens, last_refill), 'EX', 120)
                return 0
            end
            """;

    private final RedisScript<Long> script;
    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = RedisScript.of(LUA_SCRIPT, Long.class);
    }

    @Override
    public boolean tryConsume(UUID tenantId, int requestsPerMinute, int burstSize) {
        String key = "rate_limit:" + tenantId;
        long nowSeconds = System.currentTimeMillis() / 1000L;

        Long result = redisTemplate.execute(
                script,
                List.of(key),
                String.valueOf(requestsPerMinute),
                String.valueOf(burstSize),
                String.valueOf(nowSeconds)
        );

        return Long.valueOf(1L).equals(result);
    }
}
