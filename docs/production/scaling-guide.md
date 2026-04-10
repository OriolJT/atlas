# Atlas Scaling Guide

This guide covers horizontal scaling for each Atlas service, Kafka partition management, database read replicas, Redis HA, and the outbox poller leader election constraint.

---

## Horizontal Scaling Per Service

Not all Atlas services scale the same way.

### Identity Service — Scale Freely

Identity Service is stateless for request handling. All state lives in PostgreSQL. Multiple instances behind a load balancer work without any configuration changes.

**Scaling considerations:**
- Each instance maintains an in-memory permission cache bootstrapped from the database at startup. This is read-only and eventually consistent via Kafka `role.permissions_changed` events. Multiple instances each hold their own cache — this is correct behavior.
- The rate limiter uses Redis, so rate limits are enforced across instances automatically.
- Connection pool: divide the total desired Identity connections across instances. If you want 10 total connections and run 2 instances, set `maximum-pool-size: 5` per instance.

**Recommended scaling trigger:** CPU > 70% sustained, or request p99 latency > 500ms.

### Workflow Service — Scale With Care

Workflow Service can be horizontally scaled for request handling, but the background schedulers require coordination.

**What scales freely:**
- REST API request handling (start execution, query status, cancel, signal).
- The Kafka consumer (`workflow.steps.result`) — Kafka consumer group rebalancing handles this automatically. Maximum useful consumers = number of `workflow.steps.result` partitions (12 by default).

**What requires caution — the outbox poller:**
The `OutboxPublisher` scheduler runs on every instance. If two instances run simultaneously, they will both attempt to publish the same unpublished outbox rows. The `markPublished()` write prevents double-delivery in most cases, but there is a race window: both instances can read the same row as unpublished, both send to Kafka, and both attempt to mark it published. The second `save()` call is a no-op but Kafka already received two copies.

**Current state:** This is a known gap. For production deployments running more than one Workflow Service instance, you must either:

1. **Use a distributed lock for the outbox poller.** Acquire a Redis lock at the start of each `publishPending()` run; skip if the lock cannot be acquired. This ensures only one poller runs at a time.

   ```java
   @Scheduled(fixedDelay = 500)
   public void publishPending() {
       Boolean locked = redisTemplate.opsForValue()
           .setIfAbsent("atlas:outbox-lock", instanceId, Duration.ofSeconds(5));
       if (Boolean.TRUE.equals(locked)) {
           try { doPublish(); } finally { redisTemplate.delete("atlas:outbox-lock"); }
       }
   }
   ```

2. **Run a single dedicated poller instance.** Deploy one "scheduler" instance with the poller enabled and N "worker" instances with the poller disabled (controlled by a feature flag). This is operationally simpler but requires separate deployment profiles.

The same concern applies to the timeout detector and retry scheduler. Until leader election is implemented, running a single Workflow Service instance is the safe default.

**Recommended scaling trigger for API-only replicas:** Route only API traffic to additional instances; run schedulers on a single dedicated instance.

### Worker Service — Scale Freely

Worker Service is the most horizontally scalable service in Atlas. It is stateless (no database), and each instance independently acquires step leases from Redis.

**How it scales:**
- Each instance runs `ATLAS_WORKER_CONCURRENCY` (default 4) concurrent step executors.
- Lease acquisition uses Redis `SET NX EX`. If one worker holds the lease for a step, other workers skip that step command. No duplicate execution.
- Kafka consumer group rebalancing distributes `workflow.steps.execute` partitions across instances. With 12 partitions and 4 workers, each worker handles 3 partitions. With 12 workers, each handles 1 partition.

**Scaling considerations:**
- Do not exceed one consumer per partition. Running 15 Worker instances with 12 partitions means 3 instances are idle consumers — they receive no messages until a rebalance.
- Set `ATLAS_WORKER_CONCURRENCY` based on the step workload. For I/O-heavy steps (HTTP_ACTION), a higher concurrency value (16-32) makes sense because virtual threads handle blocking I/O efficiently. For CPU-heavy steps, keep concurrency closer to core count.
- Ensure the Kafka consumer group `worker-service` rebalancing is tuned for fast rebalancing: set `session.timeout.ms: 10000` and `heartbeat.interval.ms: 3000`.

**Recommended scaling trigger:** Kafka `consumer-lag` on `workflow.steps.execute` > 500 for more than 2 minutes.

### Audit Service — Scale Freely

Audit Service is a Kafka consumer with PostgreSQL writes. Multiple instances each consume a subset of `audit.events` partitions. `INSERT ... ON CONFLICT DO NOTHING` makes all writes idempotent, so duplicate delivery during rebalance is safe.

**Scaling considerations:**
- Maximum parallelism = number of `audit.events` partitions (12 by default).
- If audit query latency is a concern, add read replicas (see below).

---

## Kafka Partition Rebalancing When Adding Workers

When you add a Worker Service instance, Kafka triggers a consumer group rebalance for the `worker-service` group. During rebalance:

1. All existing consumers in the group pause consumption.
2. The group coordinator re-assigns partitions.
3. Consumption resumes.

Rebalance duration depends on `session.timeout.ms`. With the default 30s timeout, a rebalance can pause processing for up to 30 seconds. Tune this down for faster rebalances:

```yaml
spring:
  kafka:
    consumer:
      properties:
        session.timeout.ms: 10000
        heartbeat.interval.ms: 3000
        max.poll.interval.ms: 300000
```

### Gradual Scaling

When adding workers under load:
1. Deploy the new instance.
2. Monitor the rebalance via `kafka_consumer_group_lag` — you should see lag briefly spike then normalize as partitions redistribute.
3. Confirm the new instance is consuming by checking `kafka_consumer_records_consumed_total` on the new instance.

Any steps that were mid-processing during rebalance will appear to have their leases expire (since the previous consumer stopped processing). The Workflow Service stale lease detector will eventually re-publish those steps. The lease TTL (default 60s) plus the stale lease detection interval (10s) means worst-case re-publication is ~70s after a rebalance.

---

## Database Read Replicas for Audit Queries

The Audit Service's query patterns differ significantly from its write patterns:

- **Writes:** High-frequency, small inserts from Kafka consumer. Write path must be fast.
- **Reads:** Less frequent, but potentially expensive (time range queries over `audit_events`, which can be a large table).

For production deployments with significant audit query traffic, route reads to a PostgreSQL read replica.

### Spring Boot Configuration for Read Replicas

```yaml
spring:
  datasource:
    # Primary — writes
    url: jdbc:postgresql://pg-primary:5432/atlas?currentSchema=audit
    username: atlas
    password: ${ATLAS_DB_PASSWORD}
  datasource-readonly:
    # Read replica — queries
    url: jdbc:postgresql://pg-replica:5432/atlas?currentSchema=audit
    username: atlas_readonly
    password: ${ATLAS_DB_RO_PASSWORD}
```

Configure a separate `DataSource` bean for the read replica and use it in your query repositories with `@Transactional(readOnly = true)`.

### Replication Lag

PostgreSQL streaming replication introduces lag between the primary and replica. Audit queries will reflect a slightly stale view (typically under 1s for low-latency replication). This is acceptable for audit history queries, which are inherently backward-looking. Do not route real-time health checks to the replica.

---

## Redis Sentinel Setup for HA

For production deployments, Redis Sentinel provides automatic failover. The standard Sentinel topology is:

- 1 Redis primary
- 2 Redis replicas
- 3 Sentinel processes (separate from Redis, usually co-located with replicas or on separate nodes)

### Minimal Sentinel Configuration

**sentinel.conf:**
```
port 26379
sentinel monitor mymaster <primary-ip> 6379 2
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 10000
sentinel parallel-syncs mymaster 1
```

`2` in `sentinel monitor` means 2 Sentinels must agree the primary is down before triggering failover. This prevents split-brain during network partitions. With 3 Sentinels, this is the minimum quorum.

### Spring Boot Connection

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - sentinel-1:26379
          - sentinel-2:26379
          - sentinel-3:26379
      password: ${ATLAS_REDIS_PASSWORD}
      lettuce:
        pool:
          max-active: 8
          max-idle: 4
          min-idle: 1
```

Spring Data Redis + Lettuce handles Sentinel discovery and automatic reconnection after failover transparently.

### Failover Impact on Atlas

During a Redis Sentinel failover (~5-10 seconds):
- Workers cannot acquire new leases. In-flight steps continue executing if already leased.
- The Workflow Service cannot publish to Redis delay sets.
- Once the new primary is elected, operations resume automatically.
- Steps that should have started during the failover window will be re-published by the timeout detector.

---

## Outbox Poller Leader Election Considerations

This is the primary constraint on Workflow Service horizontal scaling. The outbox poller (`OutboxPublisher.publishPending()`) runs on a fixed 500ms interval on every instance. Running it on multiple instances simultaneously risks duplicate Kafka messages.

### Why It Matters

The outbox pattern guarantees at-least-once delivery. Consumers are designed to be idempotent (duplicate step results are ignored). So duplicate outbox publishes are safe in terms of correctness — the step won't execute twice. However, duplicate messages add noise, consume Kafka bandwidth, and complicate log analysis.

### Recommended Approach: Redis-Based Leader Election

Use a Redis key with a TTL as a "leader lease":

```java
private static final String LEADER_KEY = "atlas:outbox-leader";
private static final Duration LEASE_TTL = Duration.ofSeconds(10);

@Scheduled(fixedDelay = 500)
public void publishPending() {
    Boolean isLeader = redisTemplate.opsForValue()
        .setIfAbsent(LEADER_KEY, instanceId, LEASE_TTL);
    if (!Boolean.TRUE.equals(isLeader)) {
        return; // another instance is the leader
    }
    try {
        doPublish();
    } finally {
        // Only release if we're still the leader (prevents releasing another instance's lock)
        redisTemplate.execute(releaseScript, Collections.singletonList(LEADER_KEY), instanceId);
    }
}
```

Use a Lua script for the conditional release to avoid race conditions:

```lua
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end
```

### Alternative: ShedLock

[ShedLock](https://github.com/lukas-krecan/ShedLock) is a Java library that provides distributed lock behavior for Spring `@Scheduled` methods, backed by Redis, PostgreSQL, or other stores. It's a lower-effort implementation of the same pattern:

```java
@Scheduled(fixedDelay = 500)
@SchedulerLock(name = "outbox-publisher", lockAtLeastFor = "PT0.4S", lockAtMostFor = "PT5S")
public void publishPending() { ... }
```

ShedLock integrates directly with Spring Scheduler and handles the Redis TTL and lock release lifecycle. This is the recommended path for adding leader election to Atlas without writing custom locking code.
