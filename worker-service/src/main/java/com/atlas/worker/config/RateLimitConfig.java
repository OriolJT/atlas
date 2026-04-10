package com.atlas.worker.config;

import com.atlas.common.ratelimit.RateLimitFilter;
import com.atlas.common.ratelimit.RateLimitProperties;
import com.atlas.common.ratelimit.RateLimiter;
import com.atlas.common.ratelimit.RedisRateLimiter;
import com.atlas.common.ratelimit.TenantQuotaResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    @Bean
    public RateLimiter rateLimiter(StringRedisTemplate redisTemplate) {
        return new RedisRateLimiter(redisTemplate);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(RateLimiter rateLimiter,
                                           TenantQuotaResolver tenantQuotaResolver,
                                           RateLimitProperties rateLimitProperties,
                                           ObjectMapper objectMapper) {
        return new RateLimitFilter(rateLimiter, rateLimitProperties, tenantQuotaResolver, objectMapper);
    }
}
