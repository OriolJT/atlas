package com.atlas.worker.lease;

import com.atlas.worker.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class RedisLeaseManagerIntegrationTest {

    @Autowired
    private LeaseManager leaseManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void acquireLease_succeedsWhenNoExistingLease() {
        boolean acquired = leaseManager.acquireLease("step-1", "worker-A", 30);

        assertThat(acquired).isTrue();
    }

    @Test
    void acquireLease_failsWhenAnotherWorkerHoldsLease() {
        leaseManager.acquireLease("step-1", "worker-A", 30);

        boolean acquired = leaseManager.acquireLease("step-1", "worker-B", 30);

        assertThat(acquired).isFalse();
    }

    @Test
    void releaseLease_succeedsForOwner() {
        leaseManager.acquireLease("step-1", "worker-A", 30);

        boolean released = leaseManager.releaseLease("step-1", "worker-A");

        assertThat(released).isTrue();
        assertThat(redisTemplate.hasKey("step-lease:step-1")).isFalse();
    }

    @Test
    void releaseLease_failsForNonOwner_keyStillExists() {
        leaseManager.acquireLease("step-1", "worker-A", 30);

        boolean released = leaseManager.releaseLease("step-1", "worker-B");

        assertThat(released).isFalse();
        assertThat(redisTemplate.hasKey("step-lease:step-1")).isTrue();
    }

    @Test
    void extendLease_succeedsForOwner() {
        leaseManager.acquireLease("step-1", "worker-A", 30);

        boolean extended = leaseManager.extendLease("step-1", "worker-A", 60);

        assertThat(extended).isTrue();
        Long ttl = redisTemplate.getExpire("step-lease:step-1");
        assertThat(ttl).isGreaterThan(30L);
    }

    @Test
    void acquireLease_succeedsAfterLeaseExpires() throws InterruptedException {
        leaseManager.acquireLease("step-1", "worker-A", 1);

        Thread.sleep(1500);

        boolean acquired = leaseManager.acquireLease("step-1", "worker-B", 30);

        assertThat(acquired).isTrue();
    }
}
