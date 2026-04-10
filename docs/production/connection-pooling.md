# Database Connection Pooling Guide

Atlas uses HikariCP (the Spring Boot default) for database connection pooling. This guide explains the relevant settings, how to size pools correctly for each service, and how to diagnose common problems.

---

## HikariCP Settings Explained

### Current Defaults (from application.yml)

All three database-backed services (Identity, Workflow, Audit) currently use these settings:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
```

These are appropriate for local development and light testing. They are not production values.

### Full Setting Reference

| Property | Dev Default | Description |
|---|---|---|
| `maximum-pool-size` | 10 | Maximum number of connections in the pool. This is a hard cap. |
| `minimum-idle` | 2 | Minimum idle connections HikariCP maintains. Set equal to `maximum-pool-size` for a fixed-size pool. |
| `connection-timeout` | 30000 (30s) | Max time (ms) to wait for a connection from the pool. Throws `SQLTimeoutException` if exceeded. |
| `idle-timeout` | 600000 (10m) | How long a connection can sit idle before being closed. Only relevant if `minimum-idle` < `maximum-pool-size`. |
| `max-lifetime` | 1800000 (30m) | Maximum lifetime of a connection in the pool. Connections are retired and replaced on a background thread. Should be less than the PostgreSQL `idle_in_transaction_session_timeout`. |
| `keepalive-time` | 0 (disabled) | How often HikariCP sends a keepalive packet to prevent connections from being killed by network firewalls or cloud load balancers. Set to 60000 (1m) if running in a cloud environment. |
| `leak-detection-threshold` | 0 (disabled) | If a connection is held longer than this (ms), HikariCP logs a warning. Useful for diagnosing connection leaks in development. Set to 60000 during debugging. |

### Recommended Production Configuration

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: <calculated — see below>
      minimum-idle: <same as maximum-pool-size for fixed pool>
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      keepalive-time: 60000
```

Using a fixed-size pool (`minimum-idle = maximum-pool-size`) is recommended in production. It avoids the overhead of dynamic pool sizing and makes connection count predictable for capacity planning.

---

## Pool Sizing Formula

The widely-referenced formula from the PostgreSQL wiki:

```
connections = (core_count * 2) + effective_spindle_count
```

For a modern NVMe-backed PostgreSQL instance, `effective_spindle_count` is typically 1. For a 4-core database server:

```
connections = (4 * 2) + 1 = 9
```

This is the **total** connections across all clients, not per service. Divide that total across your services.

### Practical Approach for Atlas

Atlas has three services connecting to PostgreSQL. The Workflow Service does the most work: outbox writes, state machine transitions, and background scheduler queries. The rule of thumb is to give Workflow roughly half the total connections.

For a 4-core database server (total ~9-10 connections):

| Service | Pool Size |
|---|---|
| Identity | 3 |
| Workflow | 5 |
| Audit | 2 |

For a 8-core database server (total ~17-18 connections):

| Service | Pool Size |
|---|---|
| Identity | 5 |
| Workflow | 9 |
| Audit | 3 |

Scale these up proportionally for larger database servers. When running multiple instances of a service (horizontal scaling), divide the per-service pool size across instances. If you're running 2 Workflow Service instances and want 10 total Workflow connections, configure each instance with `maximum-pool-size: 5`.

### Virtual Threads and Pool Sizing

Atlas uses Java virtual threads (`spring.threads.virtual.enabled: true`). Virtual threads are cheap to create and block without tying up a platform thread, but they still hold a database connection for the duration of a transaction. Pool sizing logic does not change for virtual threads — the limiting resource is still the number of PostgreSQL connections, not the number of threads.

The benefit of virtual threads is that a high volume of concurrent requests waiting for a connection will not exhaust the platform thread pool. Under virtual threads, requests queue in HikariCP's connection wait queue rather than blocking platform threads.

---

## Per-Service Workload Patterns

### Identity Service

**Pattern:** Moderate concurrent requests, short transactions. Authentication, token validation, RBAC lookups. Burst-prone around login events.

**Recommendation:** A pool of 5-20 depending on traffic. No long-running transactions. The main risk is burst traffic during authentication storms — ensure `connection-timeout` is set so requests fail fast rather than queuing indefinitely.

### Workflow Service

**Pattern:** Mixed. Client-facing requests are short (start execution, query status). Background schedulers (outbox poller every 500ms, retry scheduler every 1s, timeout detector every 10s) hold connections briefly but frequently.

**Recommendation:** This service needs the largest pool. Give it the most headroom. Under load, the outbox poller and retry scheduler compete with request handling for connections. If `hikaricp_connections_pending` spikes, this is the service to tune first.

Consider enabling `leak-detection-threshold: 60000` in staging to catch any cases where a connection is held across an asynchronous operation boundary.

### Audit Service

**Pattern:** High-write, low-read. Bulk inserts from Kafka consumer. Occasional read queries for audit history. Transactions are short (`INSERT ... ON CONFLICT DO NOTHING`).

**Recommendation:** Smaller pool is fine. The Kafka consumer processes events sequentially within a partition. Even with 12 partitions consumed concurrently, a pool of 10-15 connections is sufficient for most workloads.

---

## Monitoring Pool Usage via Micrometer

HikariCP exposes metrics via Micrometer automatically. The metrics are prefixed `hikaricp_connections`:

| Metric | Description |
|---|---|
| `hikaricp_connections_active` | Connections currently in use by a query |
| `hikaricp_connections_idle` | Connections in the pool but not in use |
| `hikaricp_connections_pending` | Threads waiting for a connection |
| `hikaricp_connections_max` | Maximum pool size (`maximum-pool-size`) |
| `hikaricp_connections_min` | Minimum idle (`minimum-idle`) |
| `hikaricp_connections_timeout_total` | Total connection acquisition timeouts |
| `hikaricp_connections_acquire_seconds` | Time spent waiting to acquire a connection |

All metrics have a `pool` label identifying the HikariCP pool name (defaults to `HikariPool-1`).

### Prometheus Alert Rules

```yaml
groups:
  - name: atlas-connection-pool
    rules:
      - alert: ConnectionPoolExhausted
        expr: hikaricp_connections_pending > 0
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "HikariCP pool has pending waiters on {{ $labels.instance }}"

      - alert: ConnectionPoolTimeouts
        expr: increase(hikaricp_connections_timeout_total[5m]) > 0
        labels:
          severity: critical
        annotations:
          summary: "Connection acquisition timeouts on {{ $labels.instance }}"

      - alert: ConnectionAcquisitionSlow
        expr: hikaricp_connections_acquire_seconds_max > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Slow connection acquisition on {{ $labels.instance }}"
```

---

## Common Issues

### Connection Leaks

**Symptom:** `hikaricp_connections_active` increases over time but never decreases. Eventually the pool exhausts and requests start queuing or timing out.

**Cause:** A connection is acquired (transaction started) but never released. Common in code that catches exceptions without re-throwing, or that opens a transaction on a background thread and never commits or rolls back.

**Diagnosis:** Set `spring.datasource.hikari.leak-detection-threshold: 30000` (30s) in a non-production environment. HikariCP will log a stack trace when a connection is held longer than the threshold.

**Fix:** Ensure all code paths that enter a transaction exit it (commit or rollback). Spring's `@Transactional` handles this automatically if the exception propagates; the leak usually comes from catching the exception and swallowing it.

### Idle Connection Timeout Mismatches

**Symptom:** `com.zaxxer.hikari.pool.PoolBase - Failed to validate connection` errors in logs, followed by reconnection.

**Cause:** The connection was closed by the database or a network device while sitting idle in the pool. HikariCP's `max-lifetime` is set longer than PostgreSQL's `tcp_keepalives_idle` or a cloud load balancer's idle timeout.

**Fix:** Set `keepalive-time: 60000` (send keepalive packets every minute). Ensure `max-lifetime` is at least 30s shorter than PostgreSQL's `tcp_keepalives_idle` setting.

### Pool Exhaustion Under Load

**Symptom:** `hikaricp_connections_pending` spikes during traffic bursts. Requests fail with `Unable to acquire JDBC Connection` after the `connection-timeout` expires.

**Cause:** The pool is too small for the request concurrency, or transactions are held too long.

**Diagnosis:** 
1. Check `hikaricp_connections_acquire_seconds_max` — if it's climbing, the pool is undersized.
2. Check `hikaricp_connections_active` — if it's always at `maximum-pool-size`, you need a larger pool or faster transactions.
3. Profile slow queries. Long-running transactions hold connections.

**Fix:** Increase `maximum-pool-size` (accounting for total PostgreSQL connection limits), or reduce transaction duration by moving non-transactional work (e.g., HTTP calls) outside transaction boundaries.
