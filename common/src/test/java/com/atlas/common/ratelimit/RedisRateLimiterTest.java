package com.atlas.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisRateLimiterTest {

    private StringRedisTemplate redisTemplate;
    private RedisRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        rateLimiter = new RedisRateLimiter(redisTemplate);
    }

    @Test
    void tryConsume_allowed_whenScriptReturnsOne() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenReturn(1L);

        boolean result = rateLimiter.tryConsume(tenantId, 60, 10);

        assertThat(result).isTrue();
    }

    @Test
    void tryConsume_denied_whenScriptReturnsZero() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenReturn(0L);

        boolean result = rateLimiter.tryConsume(tenantId, 60, 10);

        assertThat(result).isFalse();
    }

    @Test
    void tryConsume_denied_whenScriptReturnsNull() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenReturn(null);

        boolean result = rateLimiter.tryConsume(tenantId, 60, 10);

        assertThat(result).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void tryConsume_usesCorrectKeyFormat() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenReturn(1L);

        rateLimiter.tryConsume(tenantId, 60, 10);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(
                any(RedisScript.class), keysCaptor.capture(),
                anyString(), anyString(), anyString());

        List<String> keys = keysCaptor.getValue();
        assertThat(keys).hasSize(1);
        assertThat(keys.getFirst()).isEqualTo("rate_limit:" + tenantId);
    }

    @Test
    void tryConsume_passesCorrectArguments() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenReturn(1L);

        rateLimiter.tryConsume(tenantId, 120, 20);

        ArgumentCaptor<String> arg1 = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> arg2 = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> arg3 = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(
                any(RedisScript.class), anyList(),
                arg1.capture(), arg2.capture(), arg3.capture());

        assertThat(arg1.getValue()).isEqualTo("120");
        assertThat(arg2.getValue()).isEqualTo("20");
        // arg3 is the epoch seconds -- verify it's a numeric string
        assertThat(arg3.getValue()).matches("\\d+");
    }

    @Test
    void differentTenants_useDifferentKeys() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString()))
                .thenReturn(1L);

        rateLimiter.tryConsume(tenantA, 60, 10);
        rateLimiter.tryConsume(tenantB, 60, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate, times(2)).execute(
                any(RedisScript.class), keysCaptor.capture(),
                anyString(), anyString(), anyString());

        List<List<String>> allKeys = keysCaptor.getAllValues();
        assertThat(allKeys.get(0).getFirst()).isNotEqualTo(allKeys.get(1).getFirst());
        assertThat(allKeys.get(0).getFirst()).isEqualTo("rate_limit:" + tenantA);
        assertThat(allKeys.get(1).getFirst()).isEqualTo("rate_limit:" + tenantB);
    }
}
