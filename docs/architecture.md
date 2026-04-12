# Atlas Architecture

## Overview

Atlas is a multi-tenant distributed platform for durable workflow orchestration and asynchronous event processing. It is composed of four independent services that communicate via Kafka for asynchronous operations, REST for synchronous queries, and optionally gRPC for low-latency worker-to-workflow step result reporting. An admin console (React/Vite) provides a web UI for operational visibility.

---

## Architecture Diagram

```mermaid
graph TB
    Client([Client / API Consumer])

    subgraph Atlas Platform
        IS[Identity Service<br/>:8081]
        WS[Workflow Service<br/>:8082]
        WK[Worker Service<br/>:8083]
        AS[Audit Service<br/>:8084]
        AC[Admin Console<br/>:3001]
    end

    subgraph Infrastructure
        PG[(PostgreSQL<br/>identity | workflow | audit)]
        KF{{Kafka KRaft<br/>:9092}}
        RD[(Redis<br/>:6379)]
    end

    subgraph Observability
        PR[Prometheus<br/>:9090]
        GR[Grafana<br/>:3000]
        TP[Tempo<br/>:3200]
    end

    Client -->|REST + JWT| IS
    Client -->|REST + JWT| WS
    Client -->|REST + JWT| AS

    IS -->|read/write| PG
    WS -->|read/write| PG
    AS -->|append-only| PG

    IS -->|outbox → domain.events + audit.events| KF
    WS -->|outbox → steps.execute + audit.events| KF
    WK -->|steps.result| KF

    KF -->|steps.execute| WK
    KF -->|steps.result| WS
    WK -.->|gRPC :9095 steps.result| WS
    KF -->|audit.events| AS
    KF -->|domain.events| WS

    AC -->|REST| IS
    AC -->|REST| WS

    WK -->|lease + heartbeat| RD
    WS -->|delay scheduling| RD

    IS -.->|/actuator/prometheus| PR
    WS -.->|/actuator/prometheus| PR
    WK -.->|/actuator/prometheus| PR
    AS -.->|/actuator/prometheus| PR
    PR -.-> GR
    TP -.-> GR

    IS -.->|traces| TP
    WS -.->|traces| TP
    WK -.->|traces| TP
    AS -.->|traces| TP

    WS -->|GET /internal/permissions| IS
```

---

## Orchestration Model

Atlas uses an orchestration-first architecture. The Workflow Service is the central coordinator that owns all execution state, schedules steps, and drives the Worker Service via Kafka commands. Workers are stateless executors — they receive a step command, execute it, and publish the result back.

**Event flow:**

1. Workflow Service writes an outbox row transactionally alongside the execution state change.
2. The outbox poller (every 500ms) publishes the `step.execute` command to Kafka.
3. Worker Service consumes the command, acquires a Redis lease, and executes the step.
4. Worker publishes the result to `workflow.steps.result` (via Kafka by default, or directly via gRPC on port 9095 when `ATLAS_WORKER_RESULT_TRANSPORT=grpc`).
5. Workflow Service consumes the result and advances the state machine.

**Why orchestration over choreography:**

- Execution logic lives in one place; no distributed state to reconstruct.
- Compensation order is driven by the coordinator, not by individual services.
- Debugging is straightforward — all state is in one service's database.
- The execution timeline is reconstructable from a single table.

---

## Service Architecture

### Identity Service (port 8081)

Handles authentication, tenant management, user management, RBAC, and token lifecycle. Issues JWT access tokens (15 min) and refresh tokens (7 days). Login requires `tenantSlug`, `email`, and `password` to scope authentication to a specific tenant. Permissions are not embedded in the JWT; each service maintains a local in-memory permission cache bootstrapped from Identity Service on startup and kept warm via `role.permissions_changed` domain events.

BCrypt strength 12 for password hashing. Account lockout after 5 consecutive failures for 15 minutes.

**Database schema:** `identity`  
**Tables:** `tenants`, `users`, `service_accounts`, `roles`, `permissions`, `role_permissions`, `user_roles`, `refresh_tokens`, `outbox`

### Workflow Service (port 8082)

Manages workflow definitions and execution lifecycle. Owns the state machine, step scheduling, retry logic, compensation orchestration, and dead-letter handling. All Kafka publishing goes through the outbox pattern to guarantee consistency with database state.

**Database schema:** `workflow`  
**Tables:** `workflow_definitions`, `workflow_executions`, `step_executions`, `dead_letter_items`, `outbox`

**Execution states:**
```
PENDING → RUNNING → WAITING → COMPLETED
RUNNING → FAILED → COMPENSATING → COMPENSATED
COMPENSATING → COMPENSATION_FAILED
PENDING / RUNNING / WAITING → CANCELED
RUNNING → TIMED_OUT
```

**Step states:**
```
PENDING → LEASED → RUNNING → SUCCEEDED
RUNNING → FAILED → RETRY_SCHEDULED → PENDING (retry loop)
FAILED → DEAD_LETTERED
SUCCEEDED → COMPENSATING → COMPENSATED
COMPENSATING → COMPENSATION_FAILED → DEAD_LETTERED
```

**Schedulers:**

| Scheduler | Interval | Purpose |
|-----------|----------|---------|
| Outbox poller | 500ms | Publish pending outbox events to Kafka |
| Timeout detector | 10s | Check for steps in RUNNING/LEASED past timeout |
| Stale lease detector | 10s | Check for steps leased longer than configured timeout |
| Retry scheduler | 1s | Re-publish RETRY_SCHEDULED steps past `next_retry_at` |
| Outbox cleanup | 1h | Delete published outbox rows older than 24h |

### Worker Service (port 8083)

Stateless step executor. No database schema. All durable state belongs to the Workflow Service. Redis is used only for ephemeral lease management.

Consumes `step.execute` commands, acquires a Redis lease using `SET NX EX`, executes the step, and publishes the result. Results can be published via Kafka (default) or gRPC (port 9095 on the Workflow Service) for lower latency, controlled by `ATLAS_WORKER_RESULT_TRANSPORT`. A heartbeat thread extends the lease during execution. If the lease cannot be acquired (another worker holds it), the command is skipped.

The `StepResultProcessor` in the Workflow Service respects the `non_retryable` flag on step results. When a worker reports a failure with `non_retryable: true`, the step bypasses retry logic and is immediately dead-lettered regardless of remaining retry budget.

**Step executor types:**
- `HTTP_ACTION` — HTTP call to a configured URL, timeout-aware via `RestClient`
- `INTERNAL_COMMAND` — Registered command handler by name; used for demo workflows
- `DELAY` — Publishes a `DELAY_REQUESTED` result; the Workflow Service schedules re-publication via Redis sorted sets
- `EVENT_WAIT` — Publishes a `WAITING` result immediately; a later signal or timeout advances the step
- `COMPENSATION` — Same mechanics as HTTP/internal, tagged as a compensation step

### Audit Service (port 8084)

Append-only ingestion and queryable storage of audit events. Consumes from the `audit.events` Kafka topic. All writes use `INSERT ... ON CONFLICT DO NOTHING` for idempotent ingestion. No updates or deletes ever occur on the `audit_events` table.

**Database schema:** `audit`  
**Table:** `audit_events` (audit_event_id, tenant_id, actor_type, actor_id, event_type, resource_type, resource_id, payload, correlation_id, occurred_at)

### Admin Console (port 3001)

A React/Vite single-page application providing operational visibility into Atlas. Communicates with Identity Service and Workflow Service via their REST APIs. Served as a static build behind an nginx container in Docker Compose.

---

## Communication Patterns

### Asynchronous (Kafka)

All cross-service state changes are communicated via Kafka. Producers in Identity and Workflow services use the outbox pattern: the outbox row and the business state change are written in a single database transaction, guaranteeing the event is never lost even if Kafka is temporarily unavailable.

| Topic | Producer | Consumer | Partition Key |
|-------|----------|----------|---------------|
| `workflow.steps.execute` | Workflow Service | Worker Service | `tenant_id` |
| `workflow.steps.result` | Worker Service | Workflow Service | `tenant_id` (also available via gRPC :9095) |
| `audit.events` | Identity, Workflow | Audit Service | `tenant_id` |
| `domain.events` | Identity, Workflow | Any interested service | `tenant_id` |

### Synchronous (REST)

REST is used for client-facing APIs and for one internal call: Workflow Service calls `GET /api/v1/internal/permissions` on Identity Service at startup to warm its permission cache.

### Synchronous (gRPC)

Worker Service can optionally report step results directly to Workflow Service via gRPC (port 9095) instead of Kafka. This reduces result delivery latency by bypassing the outbox-to-Kafka path. Enabled by setting `ATLAS_WORKER_RESULT_TRANSPORT=grpc` on the Worker Service. The Workflow Service gRPC server is enabled with `ATLAS_GRPC_SERVER_ENABLED=true`.

---

## API Versioning

All service endpoints are prefixed with `/api/v1/`. This allows non-breaking evolution of existing endpoints and introduction of `/api/v2/` when contracts change. Version is in the URL path rather than headers for simplicity and discoverability.

---

## Cancellation Flow

When `POST /api/v1/workflow-executions/{id}/cancel` is called:

1. The execution must be in `PENDING`, `RUNNING`, or `WAITING` state. Any other state returns `409 Conflict` (`ATLAS-WF-007`).
2. The execution transitions to `CANCELED` and `canceled_at` is set.
3. If a step is currently `LEASED` or `RUNNING`, cancellation is graceful: when the worker reports the step result, the Workflow Service ignores it and does not advance to the next step.
4. Cancellation does not trigger compensation. It is an explicit operator decision, not a failure condition.
5. Cancellation is audited.

Cancellation during `COMPENSATING` is not allowed — compensation must complete or fail on its own terms.

---

## Multi-Tenancy

`tenant_id` is present on every row in every tenant-scoped table. Enforcement uses three layers:

1. **Repository layer:** all queries include `WHERE tenant_id = :tenantId`
2. **Hibernate `@Filter`:** safety net applied to all tenant-scoped entities
3. **`TenantScopeFilter`:** validates that the `tenant_id` in the JWT matches the request scope on every incoming request

`TenantContext` is a request-scoped Spring bean (not a `ThreadLocal`) and is safe with virtual threads.

---

## Key Technology Decisions

| Concern | Choice | Rationale |
|---------|--------|-----------|
| Framework | Spring Boot 3 (latest LTS) | Broad ecosystem, production-proven, virtual thread support |
| Java | 25 (LTS) | Virtual threads, latest language features |
| Build | Multi-module Maven | Shared contracts via `common` module without duplication |
| Database | PostgreSQL (shared, schema-per-service) | Enforces data ownership, lean infrastructure |
| Messaging | Kafka (KRaft, no Zookeeper) + outbox pattern | At-least-once delivery, transactional consistency |
| Leases/Cache | Redis | Ephemeral state; loss is recoverable via timeout detection |
| Auth | JWT HS256, BCrypt | Simple symmetric signing for v1; evolvable to RS256 |
| Migrations | Flyway per service | Each service owns its schema evolution |
| Testing | JUnit 5, Testcontainers | Real infrastructure in integration tests |
| Observability | Micrometer + Prometheus + Grafana, Brave + Tempo, Logback JSON | Three pillars fully correlated |
