# Atlas Production Deployment Guide

This guide covers deploying Atlas to a production environment. It assumes familiarity with the architecture; see `docs/architecture.md` for service topology.

---

## Environment Variables Reference

All Atlas services share a common set of infrastructure variables. Service-specific variables are listed per service below.

### Shared (all services)

| Variable | Default | Description |
|---|---|---|
| `ATLAS_DB_HOST` | `localhost` | PostgreSQL host |
| `ATLAS_DB_PORT` | `5432` | PostgreSQL port |
| `ATLAS_DB_NAME` | `atlas` | PostgreSQL database name |
| `ATLAS_DB_USER` | `atlas` | PostgreSQL username |
| `ATLAS_DB_PASSWORD` | _(none)_ | PostgreSQL password — **use a secret manager** |
| `ATLAS_KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka bootstrap servers (comma-separated for multiple brokers) |
| `ATLAS_REDIS_HOST` | `localhost` | Redis host |
| `ATLAS_REDIS_PORT` | `6379` | Redis port |
| `ATLAS_JWT_SECRET` | _(none)_ | HS256 signing secret — **use a secret manager; minimum 256 bits** |
| `ATLAS_TEMPO_ENDPOINT` | `http://localhost:4318/v1/traces` | OpenTelemetry traces endpoint |

### Identity Service

| Variable | Default | Description |
|---|---|---|
| `ATLAS_IDENTITY_PORT` | `8081` | HTTP listen port |

### Workflow Service

| Variable | Default | Description |
|---|---|---|
| `ATLAS_WORKFLOW_PORT` | `8082` | HTTP listen port |
| `ATLAS_GRPC_SERVER_ENABLED` | `false` | Enable gRPC result transport endpoint |
| `ATLAS_GRPC_SERVER_PORT` | `9090` | gRPC listen port (if enabled) |

### Worker Service

| Variable | Default | Description |
|---|---|---|
| `ATLAS_WORKER_PORT` | `8083` | HTTP listen port |
| `ATLAS_WORKER_CONCURRENCY` | `4` | Parallel step execution slots per instance |
| `ATLAS_WORKER_DRAIN_TIMEOUT_S` | `30` | Seconds to wait for in-flight steps on graceful shutdown |
| `ATLAS_WORKER_RESULT_TRANSPORT` | `kafka` | `kafka` or `grpc` — transport for step results |
| `ATLAS_GRPC_WORKFLOW_HOST` | `localhost` | Workflow Service gRPC host (if using gRPC transport) |
| `ATLAS_GRPC_WORKFLOW_PORT` | `9090` | Workflow Service gRPC port (if using gRPC transport) |

### Audit Service

| Variable | Default | Description |
|---|---|---|
| `ATLAS_AUDIT_PORT` | `8084` | HTTP listen port |

---

## Database Setup

### PostgreSQL Requirements

- PostgreSQL 15 or later recommended.
- A single database (`atlas`) with four schemas: `identity`, `workflow`, `audit`. Worker Service has no database.
- Each service connects to the same database but scopes to its own schema via `currentSchema` in the JDBC URL.

### Creating the Database

```sql
CREATE DATABASE atlas;
CREATE USER atlas WITH ENCRYPTED PASSWORD '<password>';
GRANT ALL PRIVILEGES ON DATABASE atlas TO atlas;

-- Connect to the atlas database, then:
CREATE SCHEMA identity AUTHORIZATION atlas;
CREATE SCHEMA workflow AUTHORIZATION atlas;
CREATE SCHEMA audit AUTHORIZATION atlas;
```

Flyway runs automatically on service startup and applies all migrations. The `atlas` user must have schema-level CREATE privileges for the initial migration run.

### Connection Pooling

See `docs/production/connection-pooling.md` for detailed HikariCP tuning. Recommended production pool sizes:

| Service | `maximum-pool-size` | `minimum-idle` |
|---|---|---|
| Identity | 20 | 5 |
| Workflow | 30 | 5 |
| Audit | 20 | 5 |

Set these via Spring Boot properties or by overriding in your deployment configuration:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

### Separate PostgreSQL Instances (Recommended)

For production workloads with meaningful traffic, the Workflow Service database benefits from its own PostgreSQL instance. The Workflow Service does the most database work: frequent outbox writes, state machine updates on step results, background scheduler queries every 500ms–10s.

Minimum recommended: separate instances for Workflow and for Identity+Audit. Worker Service has no database dependency.

---

## Kafka Cluster Requirements

### Version and Mode

Kafka 3.6+ with KRaft mode (no ZooKeeper). Atlas does not use ZooKeeper-dependent features.

### Topics and Configuration

Atlas uses four topics. Create them before deploying services:

```bash
kafka-topics.sh --bootstrap-server <broker> --create \
  --topic workflow.steps.execute \
  --partitions 12 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config retention.ms=604800000  # 7 days

kafka-topics.sh --bootstrap-server <broker> --create \
  --topic workflow.steps.result \
  --partitions 12 \
  --replication-factor 3 \
  --config min.insync.replicas=2

kafka-topics.sh --bootstrap-server <broker> --create \
  --topic audit.events \
  --partitions 12 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config retention.ms=2592000000  # 30 days

kafka-topics.sh --bootstrap-server <broker> --create \
  --topic domain.events \
  --partitions 12 \
  --replication-factor 3 \
  --config min.insync.replicas=2
```

### Partition Count Guidance

Atlas partitions by `tenant_id`. Partition count controls maximum consumer parallelism. Twelve partitions allows up to twelve concurrent consumers in each consumer group. Scale up if you have more than 12 active tenants with concurrent workloads and need higher throughput.

Replication factor 3 with `min.insync.replicas=2` means one broker can be lost without losing availability or data. Do not run production Kafka with fewer than 3 brokers.

### Producer Configuration

All Atlas producers use `acks=all`, which requires all in-sync replicas to acknowledge a write. This is the correct setting given the outbox pattern — the synchronous Kafka send in `OutboxPublisher` blocks until the broker confirms, which is why `min.insync.replicas=2` matters.

---

## Redis HA Considerations

### Redis Sentinel (Recommended for Most Deployments)

Redis Sentinel provides automatic failover with a primary + two replicas + three Sentinel processes. This is the recommended setup for most Atlas deployments.

Configure Spring Boot to use Sentinel:

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
      password: <redis-password>
```

Remove the `ATLAS_REDIS_HOST` / `ATLAS_REDIS_PORT` env vars when using Sentinel configuration.

### Redis Cluster

Redis Cluster is appropriate for very high throughput requirements or when the keyspace is too large for a single primary. Atlas's Redis usage is lightweight — ephemeral step leases (SET NX EX) and delay scheduling (sorted sets) — so Sentinel is almost always sufficient.

### Impact of Redis Unavailability

Redis is not on the critical path for data durability. If Redis becomes unavailable:

- Workers cannot acquire leases. Active step execution will pause until Redis recovers.
- Delay steps will not be re-published on schedule.
- The Workflow Service timeout detector will eventually re-publish overdue steps as if they timed out, recovering execution.

No data is lost during a Redis outage. Recovery is automatic once Redis comes back.

---

## JWT Secret Management

### Requirements

The `ATLAS_JWT_SECRET` must be at least 256 bits (32 bytes). The default development value in `application.yml` is not suitable for production. Generate a proper secret:

```bash
openssl rand -base64 64
```

### Secret Manager Integration

Do not store the JWT secret in environment files or version control. Use a proper secret manager:

- **AWS**: Secrets Manager or SSM Parameter Store with `aws secretsmanager get-secret-value`
- **GCP**: Secret Manager with Workload Identity
- **HashiCorp Vault**: `vault kv get` with AppRole or Kubernetes auth
- **Kubernetes**: Sealed Secrets or External Secrets Operator

Inject the secret as an environment variable at runtime, not at build time.

### Key Rotation

Rotating the JWT secret requires a coordinated deployment across all four services (all services share the key for HS256 validation). Steps:

1. Generate a new secret.
2. Deploy all services simultaneously with the new secret (blue/green or rolling with a short overlap window).
3. During the overlap window, tokens signed with the old key will fail validation. This is acceptable if the access token expiry is 15 minutes — users will re-authenticate.

For zero-downtime key rotation, migrate to RS256/OIDC. See `docs/production/oidc-migration.md`.

---

## Recommended Resource Sizing

These are starting points for a moderate workload (up to 50 concurrent tenants, ~1000 workflow executions/hour).

| Service | CPU Request | CPU Limit | Memory Request | Memory Limit | Replicas |
|---|---|---|---|---|---|
| Identity | 250m | 1000m | 512Mi | 1Gi | 2 |
| Workflow | 500m | 2000m | 1Gi | 2Gi | 2 |
| Worker | 500m | 2000m | 512Mi | 1Gi | 2–4 |
| Audit | 250m | 1000m | 512Mi | 1Gi | 2 |

Worker Service scales horizontally. See `docs/production/scaling-guide.md`.

Virtual threads are enabled on all services (`spring.threads.virtual.enabled: true`). This means thread pool sizing is largely irrelevant — the JVM schedules virtual threads on platform threads automatically. Memory is the more important constraint.

---

## Health Check Endpoints

All services expose Spring Boot Actuator health endpoints. Use these for load balancer health checks and container readiness probes.

| Endpoint | Path | Notes |
|---|---|---|
| Liveness | `GET /actuator/health/liveness` | JVM is up. Use for Kubernetes `livenessProbe`. |
| Readiness | `GET /actuator/health/readiness` | DB, Kafka, Redis connectivity. Use for `readinessProbe`. |
| Full health | `GET /actuator/health` | Detailed component health. Do not expose publicly. |

Example Kubernetes probe configuration:

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8082
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8082
  initialDelaySeconds: 15
  periodSeconds: 5
  failureThreshold: 3
```

---

## Monitoring Setup

### Prometheus Scrape Configuration

All services expose metrics at `/actuator/prometheus`. Add scrape targets to your Prometheus configuration:

```yaml
scrape_configs:
  - job_name: 'atlas-identity'
    static_configs:
      - targets: ['identity-service:8081']
    metrics_path: /actuator/prometheus
    scrape_interval: 15s

  - job_name: 'atlas-workflow'
    static_configs:
      - targets: ['workflow-service:8082']
    metrics_path: /actuator/prometheus
    scrape_interval: 15s

  - job_name: 'atlas-worker'
    static_configs:
      - targets: ['worker-service:8083']
    metrics_path: /actuator/prometheus
    scrape_interval: 15s

  - job_name: 'atlas-audit'
    static_configs:
      - targets: ['audit-service:8084']
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
```

### Key Metrics to Alert On

| Metric | Alert Condition | Meaning |
|---|---|---|
| `hikaricp_connections_pending` | > 0 for 5+ minutes | Connection pool exhaustion |
| `kafka_consumer_lag_max` | > 1000 | Consumer falling behind |
| `atlas_dead_letter_depth` (custom) | > 0 | Compensation or step failure requiring operator attention |
| `jvm_memory_used_bytes` | > 80% of limit | Memory pressure |
| `http_server_requests_seconds_max` | > 5s | Request latency spike |

### Distributed Tracing

All services send traces to OpenTelemetry via `ATLAS_TEMPO_ENDPOINT`. With Grafana Tempo:

1. Add Tempo as a data source in Grafana.
2. Set `tracing.sampling.probability: 1.0` for full trace capture in staging; reduce to `0.1` for high-traffic production.
3. Use Grafana's "Explore" view to correlate traces with logs and metrics by `trace_id`.

### Grafana Dashboard

Import the provided dashboard from `infra/grafana/` (if available). The dashboard covers:
- Request rates and error rates per service
- JVM memory and GC pressure
- HikariCP pool usage
- Kafka consumer lag per consumer group
- Outbox depth (unpublished rows)
