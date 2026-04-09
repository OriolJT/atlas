package com.atlas.worker.lease;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class RedisLeaseManager implements LeaseManager {

    private static final String KEY_PREFIX = "step-lease:";

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private static final DefaultRedisScript<Long> EXTEND_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    public RedisLeaseManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean acquireLease(String stepExecutionId, String workerId, long timeoutSeconds) {
        String key = KEY_PREFIX + stepExecutionId;
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(key, workerId, Duration.ofSeconds(timeoutSeconds));
        return Boolean.TRUE.equals(result);
    }

    @Override
    public boolean releaseLease(String stepExecutionId, String workerId) {
        String key = KEY_PREFIX + stepExecutionId;
        Long result = redisTemplate.execute(RELEASE_SCRIPT, List.of(key), workerId);
        return result != null && result == 1L;
    }

    @Override
    public boolean extendLease(String stepExecutionId, String workerId, long timeoutSeconds) {
        String key = KEY_PREFIX + stepExecutionId;
        Long result = redisTemplate.execute(EXTEND_SCRIPT, List.of(key), workerId, String.valueOf(timeoutSeconds));
        return result != null && result == 1L;
    }
}
