# Atlas Design Specification

## Multi-Tenant Distributed Workflow Orchestration and Event Processing Platform

**Date:** 2026-04-08
**Status:** Approved

---

## 1. System Overview and Goals

Atlas is a multi-tenant distributed platform for durable workflow orchestration, asynchronous execution, and reliable event processing. It is designed to demonstrate end-to-end ownership of a distributed workflow platform under real-world constraints.

Atlas enables tenants to define workflows, trigger executions, coordinate long-running business processes, manage retries and compensations, authenticate users, audit changes, and observe executions in real time.

## 2. What This Project Demonstrates

Atlas is designed to prove that its author can design, implement, and operate production-grade distributed backend systems. Specifically:

| Capability | How Atlas Demonstrates It |
|-----------|--------------------------|
| **Distributed systems design** | 4 services communicating via Kafka with at-least-once delivery, outbox pattern, idempotent consumers |
| **Durable workflow orchestration** | Saga-based execution engine with state machine, retries, compensation, dead-letter handling |
| **Failure recovery** | Lease-based crash recovery, stale lease detection, timeout handling, compensation for partial failures |
| **Multi-tenancy** | Tenant-scoped data on every table, enforced at repository + Hibernate filter layers, tenant-aware RBAC |
| **Security engineering** | JWT auth, RBAC with permission resolution, BCrypt password hashing, audit trail for privileged operations |
| **Observability** | Three-pillar observability (metrics, traces, logs) fully correlated, provisioned dashboards and alerting |
| **API design** | RESTful versioned APIs, idempotency keys, correlation IDs, consistent error payloads, cursor-based pagination |
| **Operational maturity** | Dead-letter inspection/replay, execution timeline, cancellation, one-command local setup |
| **Engineering tradeoff thinking** | Every major decision documented with rationale, alternatives considered, and evolution path |

The goal is not to be a toy demo. The goal is to make reviewers believe the author can own backend platforms, not just build APIs.

## 3. Non-Goals

Explicit boundaries on what Atlas v1 does **not** attempt:

| Non-Goal | Rationale |
|----------|-----------|
| **Exactly-once delivery** | Adds distributed transaction complexity disproportionate to benefit. At-least-once with idempotent handlers achieves effectively-once semantics at far lower cost. |
| **Cross-region replication** | Atlas runs in a single region. Multi-region introduces consensus protocols, conflict resolution, and latency tradeoffs that are out of scope for v1. |
| **UI-first experience** | Atlas is a backend platform. No admin console, no workflow designer, no execution visualizer in v1. All capabilities are API-accessible. |
| **Real external integrations** | Demo workflows use simulated step executors. No real payment gateway, inventory system, or notification service. The execution engine is real; the side effects are simulated. |
| **Workflow branching or conditional logic** | v1 supports linear sequential workflows only. Parallel execution, conditional branches, and fan-out/fan-in are future enhancements. |
| **Multi-database tenancy** | v1 uses shared database with tenant_id scoping. Schema-per-tenant and database-per-tenant are documented as evolution paths but not implemented. |
| **Custom step type plugins** | Step types are fixed (HTTP, internal command, delay, event wait, compensation). User-defined step types require a plugin API that is out of scope. |

## 4. Alternative Designs Considered

### Why Not Choreography?

In a choreography model, each service reacts to events autonomously — no central coordinator. This was rejected because:
- **Debugging is harder** — execution state is distributed across services; reconstructing "what happened" requires correlating events from multiple sources.
- **Compensation is complex** — each service must know its own compensation trigger conditions and coordinate with others. No single place to enforce reverse ordering.
- **Timeline reconstruction** — building an execution timeline requires event sourcing across all services rather than querying one execution table.

Orchestration trades some coupling (workers depend on Workflow Service commands) for significant operational simplicity.

### Why Not a Temporal/Cadence-Style Replay Engine?

Temporal-style engines achieve durability by replaying the entire workflow function from the beginning on each state recovery. This is powerful but:
- **Requires deterministic replay** — all workflow logic must be deterministic (no random, no system clock). This is a significant constraint on workflow authors.
- **Replay overhead** — long workflows with many steps replay from the beginning each time, adding latency.
- **Implementation complexity** — building a replay engine is a project unto itself, not a feature within a larger platform.

Atlas uses a simpler state-machine model: execution state is persisted after each step. Recovery reads the last persisted state and resumes from there. No replay, no determinism constraint.

### Why Kafka Instead of DB Polling?

An alternative to Kafka is having the Worker Service poll the database for pending steps. This was rejected because:
- **Polling pressure** — multiple workers polling the same table creates contention and requires careful locking (`SELECT ... FOR UPDATE SKIP LOCKED`).
- **Latency vs. load tradeoff** — short poll intervals waste CPU; long intervals add latency. Kafka push-based delivery is immediate.
- **Scaling** — adding workers with Kafka is automatic (consumer group rebalancing). DB polling requires partition assignment logic.
- **Event fan-out** — Kafka topics can have multiple consumers (workers, audit, domain events). DB polling requires separate polling per consumer.

The outbox pattern gives us transactional safety of DB writes with the delivery characteristics of Kafka.

### Why Not gRPC for Inter-Service Communication?

gRPC offers schema-enforced contracts and better performance than REST for internal communication. Rejected for v1 because:
- **REST is sufficient** — inter-service synchronous calls are rare in Atlas (JWT validation is local, most communication is async via Kafka).
- **Tooling familiarity** — REST is universally debuggable with curl, browser, Postman. gRPC requires specialized tools.
- **Incremental adoption** — gRPC can be added for specific high-throughput internal paths (e.g., worker health reporting) in v2 without changing the architecture.

## 5. Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Framework | Spring Boot 3 (latest LTS) | Broad ecosystem, strong community, production-proven |
| Java | 25 (LTS) | Virtual threads, latest language features, current LTS |
| Architecture | 4 independent services, orchestration-first | Demonstrates distributed systems thinking, clean saga pattern |
| Build | Multi-module Maven with shared `common` module | Shared contracts without duplication, single CI build |
| Database | Shared PostgreSQL, separate schemas per service | Enforces data ownership, lean infra |
| Messaging | Kafka (KRaft mode), outbox pattern for producers | At-least-once delivery, transactional consistency |
| Caching/Leases | Redis | Ephemeral lease management, delay scheduling, rate limiting |
| Auth | JWT (HS256 v1), BCrypt, RBAC | Simple symmetric signing for v1, evolvable to RS256 |
| Multi-tenancy | `tenant_id` on all rows, Hibernate `@Filter`, request-scoped bean | Virtual-thread safe, double-guard with filter + repository |
| Observability | Micrometer + Prometheus + Grafana, Brave + Tempo, Logback JSON | Three pillars fully correlated |
| Migrations | Flyway per service | Each service owns its schema evolution |
| Testing | JUnit 5, Testcontainers | Real infrastructure in tests |
| Commits | Granular by task/feature | Java, Spring Boot, and Git best practices throughout |

## 6. Project Structure

```
atlas/
├── pom.xml                          # Parent POM (dependency management, plugin config)
├── common/                          # Shared module (plain JAR, no Spring Boot plugin)
│   └── src/main/java/com/atlas/common/
│       ├── event/                   # Event envelope, domain event types, outbox entity
│       ├── security/                # JWT utils, tenant context, permission annotation
│       ├── web/                     # Error response DTOs, correlation ID filter, pagination
│       └── domain/                  # Shared value objects (TenantId, UserId, ExecutionId)
├── identity-service/                # Port 8081
│   └── src/main/java/com/atlas/identity/
├── workflow-service/                # Port 8082
│   └── src/main/java/com/atlas/workflow/
├── worker-service/                  # Port 8083
│   └── src/main/java/com/atlas/worker/
├── audit-service/                   # Port 8084
│   └── src/main/java/com/atlas/audit/
├── infra/
│   ├── docker-compose.yml
│   ├── grafana/                     # Dashboard provisioning (JSON files)
│   ├── prometheus/                  # prometheus.yml + alerts.yml
│   └── kafka/                       # Topic creation scripts
├── docs/
├── scripts/                         # seed.sh, dev helpers
└── examples/
    └── workflows/                   # Demo workflow JSON definitions
```

**Parent POM** manages: Spring Boot 3 LTS parent, Java 25, shared dependencies (Spring Web, Spring Data JPA, Spring Kafka, Spring Security, Micrometer, Flyway, Jackson, Lombok, JUnit 5, Testcontainers).

## 7. Service Architecture

### 7.1 Orchestration Model

Atlas uses orchestration-first architecture. The Workflow Service is the central brain that owns execution state, schedules steps, and drives the Worker Service via Kafka commands. Workers are stateless executors that report results back.

**Event flow:**
1. Workflow Service publishes `step.execute` commands via outbox → Kafka
2. Worker Service consumes, acquires Redis lease, executes, publishes result to Kafka
3. Workflow Service consumes result, advances state machine

**Why orchestration over choreography:**
- Clear ownership of execution logic in one place
- Easier debugging — state is centralized
- Natural fit for saga compensation (reverse order driven by coordinator)
- Execution state is always reconstructable from one service's database

### 7.2 Communication Patterns

- **Synchronous (REST):** External API calls, inter-service queries (e.g., Worker Service calling an external HTTP endpoint as part of step execution)
- **Asynchronous (Kafka):** Step execution commands, step results, audit events, domain events
- **Outbox-backed publishing:** All Kafka messages originating from Identity Service or Workflow Service go through the outbox to guarantee consistency with database state changes

### 7.3 API Versioning

All service endpoints are prefixed with `/api/v1/`. This allows non-breaking evolution of existing endpoints and introduction of `/api/v2/` when contracts change. Version is in the URL path, not headers, for simplicity and discoverability.

### 7.4 Cancellation Flow

When `POST /api/v1/workflow-executions/{id}/cancel` is called:

1. Execution must be in `PENDING`, `RUNNING`, or `WAITING` state. Other states reject with `409 Conflict`.
2. Execution transitions to `CANCELED`.
3. If a step is currently `LEASED` or `RUNNING`, the cancellation is **graceful**: the Workflow Service marks the execution as `CANCELED` and sets a `canceled_at` timestamp. When the worker reports the step result, the Workflow Service ignores the result and does not advance to the next step.
4. No compensation is triggered on cancellation — cancellation is an explicit operator decision, not a failure. If the operator wants compensation, they must trigger it manually (future Tier 2 feature).
5. Cancellation is audited.

Cancellation during `COMPENSATING` is not allowed — compensation must complete or fail on its own.

## 8. Identity Service

**Port:** 8081
**Schema:** `identity`
**Responsibility:** Authentication, tenant management, user management, RBAC, token lifecycle.

### Package Structure

```
identity/
├── config/          # Security config, JWT config, CORS
├── controller/      # REST controllers
├── domain/          # Entities: Tenant, User, Role, Permission, ServiceAccount
├── repository/      # Spring Data JPA repositories
├── service/         # Business logic
├── dto/             # Request/response DTOs
├── security/        # JWT provider, authentication filter, password encoder
└── event/           # Publishes domain events via outbox
```

### Database Tables

`tenants`, `users`, `service_accounts`, `roles`, `permissions`, `role_permissions`, `user_roles`, `refresh_tokens`, `outbox`

### Authentication Flow

1. `POST /auth/login` — validate email/password, issue JWT access token (15min) + refresh token (7d)
2. JWT payload: `sub` (userId), `tenant_id`, `roles`, `iat`, `exp`. Permissions are NOT included in the token to prevent bloat. Each service resolves permissions from a local in-memory cache. Cache is bootstrapped on startup via a REST call to Identity Service (`GET /api/v1/internal/permissions`), then kept in sync incrementally via `role.permissions_changed` events on the `domain.events` Kafka topic. If the cache is empty and the REST call fails, the service retries with backoff until it succeeds — it will not serve requests until the permission cache is warm.
3. `POST /auth/refresh` — rotate refresh token, issue new access token
4. `POST /auth/logout` — revoke refresh token, publish `token.revoked` event
5. Refresh tokens stored in `refresh_tokens` table (token_hash, user_id, tenant_id, expires_at, revoked_at). Hashed with SHA-256 — never stored in plaintext.
6. Shared HS256 signing key for v1. All services validate JWTs locally.

### RBAC Model

- Permissions are fine-grained: `workflow.read`, `workflow.execute`, `workflow.manage`, `tenant.manage`, `audit.read`
- Roles bundle permissions: `TENANT_ADMIN` (all), `WORKFLOW_OPERATOR` (workflow.*, audit.read), `VIEWER` (*.read)
- Every request: authenticate → extract tenant → verify role → verify permission
- Default roles seeded per tenant on creation

### Password Security

BCrypt with strength 12. Failed login attempts tracked per user. Lockout after 5 consecutive failures for 15 minutes.

### Events Published to Kafka (via outbox)

`tenant.created`, `user.created`, `user.role_assigned`, `role.permissions_changed`, `token.revoked`

## 9. Workflow Service

**Port:** 8082
**Schema:** `workflow`
**Responsibility:** Workflow definition management, execution lifecycle, state machine, step scheduling, compensation orchestration, outbox publishing.

### Package Structure

```
workflow/
├── config/           # Kafka consumer/producer config, security config
├── controller/       # REST controllers (definitions, executions, dead-letter, timeline)
├── domain/           # Entities: WorkflowDefinition, WorkflowExecution, StepExecution, OutboxEvent
├── repository/       # Spring Data JPA repositories
├── service/          # Definition service, execution engine, compensation engine
├── statemachine/     # Execution state transitions, step state transitions
├── scheduler/        # Timeout detector, stale lease detector, outbox poller
├── dto/              # Request/response DTOs
├── event/            # Kafka consumers (step results) and outbox-backed producers
└── security/         # JWT validation filter
```

### Database Tables

`workflow_definitions`, `workflow_executions`, `step_executions`, `dead_letter_items`, `outbox`

### Workflow Definition Structure

```json
{
  "name": "order-fulfillment",
  "version": 1,
  "trigger_type": "API",
  "steps": [
    {
      "name": "validate-order",
      "type": "INTERNAL_COMMAND",
      "retry_policy": { "max_attempts": 3, "backoff": "EXPONENTIAL", "initial_delay_ms": 1000 },
      "timeout_ms": 30000,
      "compensation": null
    },
    {
      "name": "reserve-inventory",
      "type": "HTTP_ACTION",
      "retry_policy": { "max_attempts": 3, "backoff": "FIXED", "initial_delay_ms": 2000 },
      "timeout_ms": 60000,
      "compensation": "release-inventory"
    }
  ],
  "compensations": {
    "release-inventory": { "type": "HTTP_ACTION", "config": {} }
  }
}
```

### Definition Lifecycle

`DRAFT` → `PUBLISHED`. Only `PUBLISHED` definitions can be executed. Enforced at execution creation time.

### Execution Engine

1. `POST /workflow-executions` with idempotency key → creates execution in `PENDING`, first step as `PENDING`
2. Outbox poller (every 500ms) publishes `step.execute` command to Kafka
3. Worker executes, publishes result to `workflow.steps.result`
4. Workflow Service consumes result:
   - **Success** → mark step `SUCCEEDED`, advance to next step or mark execution `COMPLETED`
   - **Failure + retries left** → mark step `RETRY_SCHEDULED`, set `next_retry_at`
   - **Failure + no retries + non-retryable** → skip to dead-letter/compensation immediately
   - **Failure + no retries** → mark step `FAILED`, trigger compensation
5. Compensation → execution enters `COMPENSATING`, runs completed steps in reverse
6. All compensations done → execution enters `COMPENSATED`

### Idempotency on Step Results

Deduplicate by `step_execution_id` + `attempt_count` before advancing state. Prevents double-processing from at-least-once delivery.

### Execution States

`PENDING` → `RUNNING` → `WAITING` → `COMPLETED`
`RUNNING` → `FAILED` → `COMPENSATING` → `COMPENSATED`
`COMPENSATING` → `COMPENSATION_FAILED` (when a compensation step itself fails and exhausts retries)
`PENDING` / `RUNNING` / `WAITING` → `CANCELED`
`RUNNING` → `TIMED_OUT`

`COMPENSATION_FAILED` is a terminal state requiring manual operator intervention. The failed compensation step is dead-lettered for inspection and potential replay.

Invalid transitions throw exceptions.

### Step States

`PENDING` → `LEASED` → `RUNNING` → `SUCCEEDED`
`RUNNING` → `WAITING` (for EVENT_WAIT steps)
`RUNNING` → `FAILED` → `RETRY_SCHEDULED` → `PENDING` (loop)
`FAILED` → `DEAD_LETTERED`
`SUCCEEDED` → `COMPENSATING` → `COMPENSATED`
`COMPENSATING` → `COMPENSATION_FAILED` → `DEAD_LETTERED`

### Schedulers

| Scheduler | Interval | Purpose |
|-----------|----------|---------|
| Outbox poller | 500ms | Publish pending outbox events to Kafka |
| Timeout detector | 10s | Check for steps in RUNNING/LEASED past timeout |
| Stale lease detector | 10s | Check for steps leased longer than timeout, mark for retry |
| Retry scheduler | 1s | Check for RETRY_SCHEDULED steps past next_retry_at, re-publish |
| Outbox cleanup | 1h | Delete published outbox rows older than 24h |

### Dead-Letter

Steps exhausting all retries move to `DEAD_LETTERED` state. A record is inserted into `dead_letter_items` with full context (step payload, error history, attempt count). Operators can inspect and replay via API.

### Signal API (for EVENT_WAIT steps)

`POST /api/v1/workflow-executions/{id}/signal` with body:

```json
{
  "step_name": "wait-for-acknowledgment",
  "payload": { "acknowledged_by": "user-123" }
}
```

Flow:
1. Validates execution is in `WAITING` state and the named step is in `WAITING` state
2. Marks step as `SUCCEEDED` with the signal payload as output
3. Advances execution to the next step (or `COMPLETED`)
4. If the signal arrives after timeout already fired, returns `409 Conflict`

This is how Demo 2's acknowledgment arrives — an external system calls the signal endpoint.

### Timeline API

`GET /api/v1/workflow-executions/{id}/timeline` returns an ordered list of execution events:

```json
{
  "execution_id": "exec-789",
  "events": [
    {
      "timestamp": "2026-04-08T14:30:00Z",
      "type": "EXECUTION_STARTED",
      "detail": { "workflow": "order-fulfillment", "version": 1 }
    },
    {
      "timestamp": "2026-04-08T14:30:01Z",
      "type": "STEP_STARTED",
      "step_name": "validate-order",
      "attempt": 1
    },
    {
      "timestamp": "2026-04-08T14:30:02Z",
      "type": "STEP_SUCCEEDED",
      "step_name": "validate-order",
      "attempt": 1,
      "duration_ms": 1200
    },
    {
      "timestamp": "2026-04-08T14:30:05Z",
      "type": "STEP_FAILED",
      "step_name": "charge-payment",
      "attempt": 1,
      "error": "Payment gateway timeout"
    },
    {
      "timestamp": "2026-04-08T14:30:08Z",
      "type": "STEP_RETRY_SCHEDULED",
      "step_name": "charge-payment",
      "attempt": 2,
      "next_retry_at": "2026-04-08T14:30:10Z"
    }
  ]
}
```

Data source: built from `step_executions` table records and their state transitions. Each state change is a timeline event. Stored as a `state_history` JSONB column on `step_executions` that appends each transition with timestamp, or reconstructed from the audit trail.

## 10. Worker Service

**Port:** 8083
**No database schema** — intentionally stateless. All durable state in Workflow Service. Redis for ephemeral leases only.
**Responsibility:** Stateless execution of workflow steps.

### Package Structure

```
worker/
├── config/           # Kafka consumer/producer config, security config, Redis config
├── executor/         # Step type executors (HTTP, internal command, delay, compensation)
├── lease/            # Lease acquisition and heartbeat management (Redis-backed)
├── consumer/         # Kafka consumer for step.execute commands
├── reporter/         # Publishes step results back to Kafka
├── health/           # Custom health indicators
└── security/         # JWT validation for admin endpoints
```

### Execution Flow

1. Consume from `workflow.steps.execute` (partitioned by `tenant_id`)
2. Acquire Redis lease: `SET step:{step_execution_id} {worker_id} NX EX {timeout_seconds}`
3. If lease acquired → execute. If not → skip (another worker got it)
4. Heartbeat thread extends lease every `timeout / 3` seconds during execution
5. On completion → publish result to `workflow.steps.result`
6. Release lease

### Step Executors (Strategy Pattern)

- **HttpActionExecutor** — HTTP call to configured URL. Timeout-aware via `RestClient`.
- **InternalCommandExecutor** — Registered command handler by name. Used for demo workflows.
- **DelayStepExecutor** — Does NOT block a worker thread. Publishes `DELAY_REQUESTED` result. Workflow Service uses Redis `ZADD` with wake-up timestamp, a poller re-publishes the step command when delay expires.
- **EventWaitExecutor** — Publishes `WAITING` result immediately. Workflow Service transitions execution to `WAITING` state. An external event (received via API or Kafka) or timeout expiry advances the step. The worker does not block.
- **CompensationExecutor** — Same as HTTP/internal but tagged as compensation.

### Poison Message Handling

Unknown step types or malformed commands → immediately publish `FAILED` result with non-retryable error flag. Workflow Service skips straight to dead-letter/compensation.

### Consumer Concurrency

Configurable via `spring.kafka.listener.concurrency` (default: 4 threads). Max concurrent executions cap to prevent resource exhaustion.

### Scaling, Shutdown, and Failure Handling

- **Horizontal scaling:** Add worker instances → Kafka consumer group redistributes partitions. Redis leases prevent duplicate execution during rebalancing.
- **Graceful shutdown:** On SIGTERM → stop consuming, drain in-flight steps (configurable timeout), publish results, release leases.
- **Exception during execution** → catch, publish `FAILED` result with error detail.
- **Worker crash** → Redis lease expires → Workflow Service timeout detector re-publishes command.
- **Kafka publish failure** → Spring Kafka built-in retry. Workflow Service deduplicates.

## 11. Audit Service

**Port:** 8084
**Schema:** `audit`
**Responsibility:** Immutable ingestion and queryable storage of audit events.

### Package Structure

```
audit/
├── config/           # Kafka consumer config, security config
├── controller/       # REST controllers (query audit events)
├── domain/           # Entity: AuditEvent
├── repository/       # Spring Data JPA repository with custom query methods
├── consumer/         # Kafka consumer for audit events from all services
├── dto/              # Query filters, paginated response DTOs
└── security/         # JWT validation, requires audit.read permission
```

### Database Table: `audit_events`

Append-only. No updates. No deletes.

| Column | Type | Notes |
|--------|------|-------|
| audit_event_id | UUID | PK |
| tenant_id | UUID | Indexed, all queries scoped by tenant |
| actor_type | VARCHAR | `USER`, `SERVICE_ACCOUNT`, `SYSTEM` |
| actor_id | UUID | Who performed the action |
| event_type | VARCHAR | e.g., `tenant.created` |
| resource_type | VARCHAR | e.g., `TENANT`, `WORKFLOW_DEFINITION` |
| resource_id | UUID | What was acted upon |
| payload | JSONB | Full event detail |
| correlation_id | UUID | Links to execution trace |
| occurred_at | TIMESTAMP | Indexed for range queries |

### Ingestion

- Consumes from `audit.events` Kafka topic with dedicated consumer group
- Deduplicates by `audit_event_id`: `INSERT ... ON CONFLICT DO NOTHING`
- No outbox needed — pure consumer, not a producer

### Query API

`GET /api/v1/audit-events?actor_id=&event_type=&resource_type=&resource_id=&from=&to=&page=&size=`

- All queries must include `tenant_id` (extracted from JWT, enforced at controller)
- Cursor-based pagination (keyset on `occurred_at` + `audit_event_id`)
- Requires `audit.read` permission

### What Gets Audited

- **Identity:** tenant creation, user creation, role changes, permission changes, token revocation, API key creation, failed login attempts
- **Workflow:** definition published, execution started/completed/failed/cancelled, compensation triggered, dead-letter replay
- **Worker:** none directly (results flow through Workflow Service)

## 12. Common Module

### Package Structure

```
common/
├── event/
│   ├── DomainEvent.java              # Base event envelope
│   ├── EventTypes.java               # Constants for all event type strings
│   └── OutboxEvent.java              # Outbox table entity
├── security/
│   ├── JwtTokenParser.java           # Shared JWT validation logic
│   ├── TenantContext.java            # Request-scoped bean (virtual-thread safe)
│   ├── AuthenticatedPrincipal.java   # Extracted identity
│   └── RequiresPermission.java       # Custom annotation for declarative permission checks
├── web/
│   ├── ErrorResponse.java            # Consistent error payload
│   ├── CorrelationIdFilter.java      # Extracts or generates X-Correlation-ID
│   ├── TenantScopeFilter.java        # Validates tenant_id in JWT matches request scope
│   └── PaginationResponse.java       # Shared paginated response wrapper
└── domain/
    ├── TenantId.java                 # Value object (UUID wrapper)
    ├── UserId.java                   # Value object
    └── ExecutionId.java              # Value object
```

### Event Envelope

Every domain event includes: `event_id`, `event_type`, `occurred_at`, `tenant_id`, `correlation_id`, `causation_id`, `idempotency_key`, `payload`.

### Multi-Tenancy Enforcement

- `TenantContext` is a request-scoped Spring bean (not ThreadLocal) — safe with virtual threads
- For Kafka consumers: tenant_id extracted from message headers and passed explicitly
- All repository queries include `WHERE tenant_id = :tenantId`
- Hibernate `@Filter`/`@FilterDef` as safety net on all tenant-scoped entities
- `TenantScopeFilter` validates JWT tenant matches request scope on every request

### Outbox Pattern

1. Business operation + outbox insert in single `@Transactional` method
2. Outbox poller (500ms) reads unpublished rows ordered by `created_at`, processes per-aggregate sequentially to guarantee ordering within an aggregate (e.g., all events for execution X are published in order)
3. On Kafka failure → row stays unpublished, retried next cycle
4. Outbox table: `id`, `aggregate_type`, `aggregate_id`, `event_type`, `topic`, `payload`, `tenant_id`, `created_at`, `published_at`. The `topic` column determines which Kafka topic the event is routed to. A single business operation can insert multiple outbox rows targeting different topics (e.g., `domain.events` for reactive consumers and `audit.events` for the audit trail).
5. Cleanup job deletes published rows older than 24h

## 13. Kafka Topology

| Topic | Producer | Consumer | Partitioning |
|-------|----------|----------|-------------|
| `workflow.steps.execute` | Workflow Service (outbox) | Worker Service | `tenant_id` |
| `workflow.steps.result` | Worker Service | Workflow Service | `tenant_id` |
| `audit.events` | Identity, Workflow (outbox) | Audit Service | `tenant_id` |
| `domain.events` | Identity, Workflow (outbox) | Any interested service | `tenant_id` |

## 14. Infrastructure (Docker Compose)

| Service | Port | Purpose |
|---------|------|---------|
| identity-service | 8081 | Auth, tenants, RBAC |
| workflow-service | 8082 | Definitions, executions, orchestration |
| worker-service | 8083 | Step execution |
| audit-service | 8084 | Audit trail |
| postgres | 5432 | Shared DB (schemas: identity, workflow, audit) |
| kafka (KRaft) | 9092 | Event transport (no Zookeeper) |
| redis | 6379 | Leases, delay scheduling, rate limiting |
| prometheus | 9090 | Metrics scraping |
| grafana | 3000 | Dashboards |
| tempo | 3200 | Distributed tracing |

All infrastructure services have `healthcheck` definitions. Application services use `depends_on: condition: service_healthy`.

## 15. Observability

### Metrics (Micrometer → Prometheus → Grafana)

Each service exposes `/actuator/prometheus`. Prometheus scrapes every 15s. All business metrics tagged with `tenant_id`.

| Metric | Service | Type |
|--------|---------|------|
| `atlas.auth.login.success` | Identity | Counter |
| `atlas.auth.login.failure` | Identity | Counter |
| `atlas.tenant.created` | Identity | Counter |
| `atlas.workflow.execution.started` | Workflow | Counter |
| `atlas.workflow.execution.completed` | Workflow | Counter |
| `atlas.workflow.execution.failed` | Workflow | Counter |
| `atlas.workflow.execution.compensating` | Workflow | Counter |
| `atlas.workflow.step.duration` | Workflow | Timer |
| `atlas.workflow.step.retries` | Workflow | Counter |
| `atlas.workflow.deadletter.count` | Workflow | Gauge |
| `atlas.worker.step.duration` | Worker | Timer |
| `atlas.worker.step.success` | Worker | Counter |
| `atlas.worker.step.failure` | Worker | Counter |
| `atlas.worker.lease.acquired` | Worker | Counter |
| `atlas.worker.lease.conflict` | Worker | Counter |
| `atlas.audit.events.ingested` | Audit | Counter |
| `atlas.audit.query.latency` | Audit | Timer |
| `atlas.kafka.consumer.lag` | All | Gauge |

### Alerting Rules (Prometheus → Grafana)

- Kafka consumer lag > threshold for 5 minutes
- Dead-letter queue depth increasing
- Execution failure rate > 10% over 5 minutes
- Service health endpoint down for 30 seconds
- Outbox unpublished rows growing (poller stuck)

Provisioned in `infra/prometheus/alerts.yml`.

### Distributed Tracing (Micrometer Tracing → Brave → Tempo)

Trace context propagated via HTTP headers (`traceparent`) and Kafka headers (`b3`). Correlation ID added as span tag (separate from Trace ID). Key spans: API entry → execution creation → outbox → Kafka → worker → result → state advance. Grafana queries Tempo directly.

### Structured Logging (Logback JSON → stdout)

All services emit JSON to stdout: `timestamp`, `level`, `service`, `traceId`, `correlationId`, `tenantId`, `executionId`, `stepName`, `message`.

### Grafana Dashboards (4, provisioned as JSON)

1. **Platform Health** — Service up/down, JVM, HTTP errors, Kafka consumer lag, DB pool
2. **Workflow Executions** — Started/completed/failed over time, step duration, compensation rate
3. **Failures & Retries** — Retry rate by step, dead-letter depth, timeout frequency
4. **Tenant Activity** — Per-tenant execution volume, API request rate, auth failure rate

## 16. API Endpoints

### Identity Service

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/auth/login` | Authenticate user, return JWT + refresh token |
| POST | `/api/v1/auth/refresh` | Rotate refresh token, issue new access token |
| POST | `/api/v1/auth/logout` | Revoke refresh token |
| POST | `/api/v1/tenants` | Create tenant |
| GET | `/api/v1/tenants/{id}` | Get tenant details |
| POST | `/api/v1/users` | Create user |
| GET | `/api/v1/users/{id}` | Get user details |
| POST | `/api/v1/roles` | Create role |
| POST | `/api/v1/roles/{id}/permissions` | Assign permissions to role |
| POST | `/api/v1/service-accounts` | Create service account |
| POST | `/api/v1/api-keys` | Create API key for service account |
| GET | `/api/v1/internal/permissions` | Fetch all role-permission mappings (internal, service-to-service) |
| GET | `/actuator/health` | Health check (no auth required) |

### Workflow Service

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/workflow-definitions` | Register workflow definition (DRAFT) |
| GET | `/api/v1/workflow-definitions/{id}` | Get definition details |
| POST | `/api/v1/workflow-definitions/{id}/publish` | Publish definition |
| POST | `/api/v1/workflow-executions` | Start execution (idempotency key required) |
| GET | `/api/v1/workflow-executions/{id}` | Get execution status |
| POST | `/api/v1/workflow-executions/{id}/cancel` | Cancel active execution |
| POST | `/api/v1/workflow-executions/{id}/signal` | Send signal to EVENT_WAIT step |
| GET | `/api/v1/workflow-executions/{id}/timeline` | Get execution event timeline |
| GET | `/api/v1/dead-letter` | List dead-letter items |
| POST | `/api/v1/dead-letter/{id}/replay` | Replay dead-letter item |
| GET | `/actuator/health` | Health check (no auth required) |

### Audit Service

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/audit-events` | Query audit events (filtered, paginated) |
| GET | `/actuator/health` | Health check (no auth required) |

### Health Check Endpoints

All services expose Spring Boot Actuator endpoints:
- `/actuator/health` — used by Docker Compose healthchecks and load balancers. Returns `UP`/`DOWN` with checks for DB connectivity, Kafka connectivity, and Redis connectivity (where applicable). No authentication required.
- `/actuator/prometheus` — Prometheus metrics scrape endpoint. No authentication required.
- `/actuator/info` — Service name, version, git commit. No authentication required.

### API Documentation (OpenAPI)

Each service auto-generates OpenAPI 3.0 specs via `springdoc-openapi`:
- `/swagger-ui.html` — interactive API explorer (enabled in dev/local profiles only)
- `/v3/api-docs` — machine-readable OpenAPI JSON (always available)
- Schemas include validation constraints, example values, and error response models
- API docs are versioned alongside the code — no separate documentation to maintain

### API Quality Standards

- Consistent error payloads (`ErrorResponse` from common module)
- Validation errors with actionable messages (Jakarta Bean Validation annotations on all DTOs)
- Cursor-based pagination where appropriate
- `X-Correlation-ID` header on all requests/responses
- `Idempotency-Key` header on workflow execution creation

### Error Code Taxonomy

All error responses include a machine-readable error code that consumers can programmatically switch on. Format: `ATLAS-{SERVICE}-{NUMBER}`.

| Code | Service | Meaning |
|------|---------|---------|
| `ATLAS-AUTH-001` | Identity | Invalid credentials |
| `ATLAS-AUTH-002` | Identity | Account locked |
| `ATLAS-AUTH-003` | Identity | Token expired |
| `ATLAS-AUTH-004` | Identity | Insufficient permissions |
| `ATLAS-AUTH-005` | Identity | Refresh token revoked |
| `ATLAS-TENANT-001` | Identity | Tenant not found |
| `ATLAS-TENANT-002` | Identity | Tenant suspended |
| `ATLAS-WF-001` | Workflow | Definition not found |
| `ATLAS-WF-002` | Workflow | Definition not published |
| `ATLAS-WF-003` | Workflow | Execution not found |
| `ATLAS-WF-004` | Workflow | Invalid state transition |
| `ATLAS-WF-005` | Workflow | Idempotency key conflict |
| `ATLAS-WF-006` | Workflow | Signal rejected (step not waiting) |
| `ATLAS-WF-007` | Workflow | Cancellation rejected (invalid state) |
| `ATLAS-DL-001` | Workflow | Dead-letter item not found |
| `ATLAS-AUDIT-001` | Audit | Invalid query parameters |
| `ATLAS-COMMON-001` | Any | Validation failed (details in `errors` array) |
| `ATLAS-COMMON-002` | Any | Tenant scope mismatch |

Error response structure:
```json
{
  "code": "ATLAS-WF-002",
  "message": "Workflow definition is not published",
  "details": "Definition 'order-fulfillment' v1 is in DRAFT state. Publish it before creating executions.",
  "correlation_id": "def456",
  "timestamp": "2026-04-08T14:32:01.123Z"
}
```

Validation errors include a nested `errors` array:
```json
{
  "code": "ATLAS-COMMON-001",
  "message": "Validation failed",
  "errors": [
    { "field": "name", "message": "must not be blank" },
    { "field": "steps", "message": "must contain at least one step" }
  ],
  "correlation_id": "def456",
  "timestamp": "2026-04-08T14:32:01.123Z"
}
```

## 17. Database Migrations (Flyway)

Each service manages its own schema. Migrations in `src/main/resources/db/migration/`.

Naming convention: `V001__create_tenants_table.sql`, `V002__create_users_table.sql`, etc.

Migrations run on service startup.

### Bootstrap

Flyway seeds the first tenant + admin user + default roles directly into the database on first startup (with BCrypt-hashed password). All subsequent setup is done via APIs.

## 18. Demo Workflows

### Demo 1: E-Commerce Order Fulfillment

5-step saga with compensation:

```
validate-order → reserve-inventory → charge-payment → create-shipment → send-notification
```

Compensation chain (on `create-shipment` failure — `send-notification` never ran, so only prior completed steps are compensated):
1. Compensate `charge-payment` → `refund-payment`
2. Compensate `reserve-inventory` → `release-inventory`
3. `validate-order` — skipped (no compensation defined, read-only step)

Note: `send-notification` has no compensation defined by design — notifications are fire-and-forget. This only matters if a hypothetical step after it fails in a future workflow version.

All steps are `INTERNAL_COMMAND` with simulated logic. Configurable failure injection via step input payload.

### Demo 2: Incident Escalation

4-step workflow with timeout-triggered escalation:

```
register-incident → assign-oncall → wait-for-acknowledgment → escalate-if-timeout
```

- `wait-for-acknowledgment`: `EVENT_WAIT` step with 60-second timeout
- Timeout expires → `escalate-if-timeout` runs
- No compensation needed

### Failure Injection

Each `INTERNAL_COMMAND` executor checks for optional `failure_config` in step input:

```json
{
  "fail_at_step": "create-shipment",
  "failure_type": "TRANSIENT",
  "fail_after_attempts": 2
}
```

- `TRANSIENT`: fails first N attempts then succeeds (showcases retries)
- `PERMANENT`: always fails (showcases compensation)
- No config: always succeeds

### Seed Data

**Bootstrap (Flyway):** First tenant "Acme Corp" + admin user + default roles

**Seed script (`scripts/seed.sh`):** Authenticates as admin, creates additional users and workflow definitions via API. Generates real audit events.

| Email | Role | Purpose |
|-------|------|---------|
| `admin@acme.com` | `TENANT_ADMIN` | Full access |
| `operator@acme.com` | `WORKFLOW_OPERATOR` | Execute and inspect workflows |
| `viewer@acme.com` | `VIEWER` | Read-only, demonstrates RBAC |

Password for all demo users: `Atlas2026!`

Seed script is idempotent — uses fixed UUIDs and idempotency keys.

## 19. Testing Strategy

### Unit Tests

- Domain rules, state machine transitions
- Retry delay calculations (fixed, exponential with jitter)
- Authorization logic, permission checks
- Compensation ordering

### Integration Tests (Testcontainers)

- PostgreSQL: repository tests, Flyway migrations
- Kafka: outbox publishing, event consumption
- Redis: lease acquisition, delay scheduling

### End-to-End Tests

- Authenticate → create definition → start execution → verify completion
- Trigger failure → verify retries → verify compensation
- Verify dead-letter on exhausted retries
- Inspect audit trail for all operations
- RBAC: verify viewer cannot execute workflows

### Failure Tests

- Duplicate event delivery (idempotency verification)
- Worker crash after lease (stale lease recovery)
- Outbox publication recovery
- Dead-letter replay

## 20. Scope Tiers

### Tier 1 (Must Ship)

- Tenant-aware auth with JWT and RBAC
- Workflow definition registration with DRAFT/PUBLISHED lifecycle
- Workflow execution engine with state machine
- Worker with retries and compensation
- Outbox pattern for Kafka publishing
- Audit trail
- Metrics, logs, tracing
- Docker Compose one-command setup
- Two demo workflows
- Seed data

### Tier 2 (If Time Allows)

- Service account API keys
- Per-tenant quotas
- Rate limiting
- Dead-letter replay API
- Delayed scheduling via Redis

### Tier 3 (Stretch)

- Admin console UI (React)
- Chaos testing suite
- gRPC internal communication

## 21. Documented Tradeoffs

The following tradeoffs must be explicitly documented in `docs/tradeoffs.md`:

1. **At-least-once over exactly-once** — simpler, achievable with idempotent handlers. Exactly-once requires distributed transactions which add complexity disproportionate to benefit.
2. **Orchestration over choreography** — centralized state makes debugging, compensation, and timeline reconstruction straightforward. Choreography distributes logic and makes failure recovery harder to reason about.
3. **Shared DB with tenant scoping over DB-per-tenant** — sufficient isolation for v1 with less operational overhead. Evolvable to schema-per-tenant or DB-per-tenant.
4. **Redis for leases and scheduling** — ephemeral by nature, lost leases are recovered by timeout detection. Redis failure = temporary inability to acquire leases, not data loss.
5. **Compensation is best-effort but durable** — compensation steps can themselves fail. Failures are recorded and visible operationally. Manual intervention is the fallback.
6. **4 services, not more** — depth over breadth. Each service has real complexity and production-grade implementation.
7. **HS256 over RS256 for v1** — simpler key management (shared secret). Evolvable to asymmetric signing when service count grows.

## 22. System Guarantees and Invariants

These are the guarantees Atlas makes under normal and failure conditions. Each is enforced by a specific mechanism and verified by tests.

### Execution Guarantees

| Guarantee | Mechanism | Verified By |
|-----------|-----------|-------------|
| A workflow step is executed **at least once, never zero times** | Outbox pattern ensures command publication; timeout detector re-publishes if worker crashes before reporting | Failure test: kill worker mid-step, verify step re-executes |
| A step result is processed **at most once per attempt** | Deduplication by `step_execution_id` + `attempt_count` in Workflow Service consumer | Failure test: publish duplicate result, verify state advances only once |
| Workflow state transitions are **monotonic and durable** | State machine rejects invalid transitions; every transition is a DB write inside a transaction | Unit test: verify all invalid transitions throw; integration test: crash after transition, verify state persists |
| Compensation executes **only for completed steps, in reverse completion order** | Compensation engine queries `SUCCEEDED` steps ordered by `finished_at DESC` | Unit test: verify ordering; E2E test: fail step 4, verify compensations run for steps 3, 2 (not 1) |
| An execution with an idempotency key **produces exactly one execution** | `UNIQUE` constraint on `(tenant_id, idempotency_key)` in `workflow_executions` | Integration test: submit same key twice, verify 409 on second |

### Tenancy Guarantees

| Guarantee | Mechanism | Verified By |
|-----------|-----------|-------------|
| Tenant data is **never accessible across tenant boundaries** | `tenant_id` on all rows + Hibernate `@Filter` + repository-level WHERE clause + `TenantScopeFilter` on every request | Integration test: authenticate as tenant A, attempt to read tenant B's data, verify 404 |
| A user can **only perform actions allowed by their roles** | `@RequiresPermission` annotation checked on every endpoint; permissions resolved from role cache | Integration test: viewer attempts workflow execution, verify 403 |

### Delivery Guarantees

| Guarantee | Mechanism | Verified By |
|-----------|-----------|-------------|
| No event is lost if Kafka is temporarily unavailable | Outbox retains unpublished rows until Kafka publish succeeds | Integration test: block Kafka, perform operations, restore Kafka, verify all events arrive |
| Audit events are **never lost, never duplicated** | At-least-once delivery + idempotent insert (`ON CONFLICT DO NOTHING`) | Failure test: publish same audit event twice, verify single row |

## 23. Failure Scenarios

Concrete walkthrough of what happens when things break. Each scenario documents the failure, the system's automatic response, and the observable outcome.

### Scenario 1: Worker Crashes Mid-Step

```
Timeline:
  T+0s    Worker picks up "charge-payment" step, acquires Redis lease (TTL=60s)
  T+5s    Worker crashes (process killed)
  T+60s   Redis lease expires automatically
  T+70s   Workflow Service timeout detector runs, finds step in LEASED state
          past timeout → marks step FAILED, checks retry policy
  T+70s   Retries remaining → marks step RETRY_SCHEDULED, sets next_retry_at
  T+72s   Retry scheduler picks up step, publishes new step.execute command
  T+73s   Another worker picks up the step, acquires new lease, executes

Observable: Execution completes successfully with attempt_count=2.
           Audit trail shows the failed attempt and retry.
           Metrics: atlas.worker.step.failure incremented, atlas.workflow.step.retries incremented.
```

### Scenario 2: Kafka Unavailable for 5 Minutes

```
Timeline:
  T+0s    Kafka goes down
  T+1s    Workflow Service completes a workflow execution start request
          DB transaction commits: execution row + outbox row written
  T+1.5s  Outbox poller tries to publish → Kafka connection fails
          Row stays unpublished, poller logs warning
  T+2s    More operations occur → more outbox rows accumulate
  T+300s  Kafka recovers
  T+300.5s Outbox poller publishes all accumulated rows in order
  T+301s  Workers pick up all pending steps, execution proceeds normally

Observable: No data loss. Execution latency increased by ~5 minutes.
           Metrics: atlas.kafka.consumer.lag spikes, then recovers.
           Alert fires: "Outbox unpublished rows growing" at T+30s.
```

### Scenario 3: Duplicate Message Delivery

```
Timeline:
  T+0s    Worker completes "reserve-inventory", publishes SUCCEEDED to Kafka
  T+1s    Kafka consumer rebalance occurs before offset commit
  T+2s    New consumer picks up same message, delivers to Workflow Service again
  T+2s    Workflow Service checks: step_execution_id + attempt_count already processed
          → result discarded, no state change

Observable: No duplicate side effects. Step advanced exactly once.
           Metrics: deduplication counter incremented.
```

### Scenario 4: Downstream Step Fails, Triggering Compensation

```
Timeline:
  T+0s    "validate-order" succeeds
  T+2s    "reserve-inventory" succeeds
  T+4s    "charge-payment" succeeds
  T+6s    "create-shipment" fails permanently (non-retryable)
  T+6s    Execution transitions to COMPENSATING
  T+7s    Compensation: "refund-payment" published (reverses "charge-payment")
  T+8s    "refund-payment" succeeds
  T+9s    Compensation: "release-inventory" published (reverses "reserve-inventory")
  T+10s   "release-inventory" succeeds
  T+10s   Execution transitions to COMPENSATED

Observable: All side effects rolled back in reverse order.
           Audit trail shows full forward + compensation history.
           Timeline API returns complete narrative of the execution.
```

### Scenario 5: Compensation Step Itself Fails

```
Timeline:
  T+0s    Execution enters COMPENSATING after step failure
  T+1s    "refund-payment" compensation step published
  T+2s    "refund-payment" fails, retried 3 times, all fail
  T+10s   "refund-payment" exhausts retries → COMPENSATION_FAILED
  T+10s   Compensation step dead-lettered
  T+10s   Execution transitions to COMPENSATION_FAILED (terminal state)

Observable: Execution stuck in COMPENSATION_FAILED.
           Dead-letter item created with full error context.
           Alert fires: dead-letter queue depth increasing.
           Operator must investigate and manually replay or resolve.
```

### Scenario 6: Redis Unavailable

```
Timeline:
  T+0s    Redis goes down
  T+1s    Worker tries to acquire lease → Redis connection fails
  T+1s    Worker cannot execute step, does not publish result
  T+60s   Workflow Service timeout detector finds step past timeout
          → marks for retry, publishes new command
  T+60s   Workers still cannot acquire leases → cycle repeats
  T+120s  Redis recovers
  T+121s  Worker acquires lease, executes step normally

Observable: Execution delayed by Redis downtime.
           No data corruption. No duplicate execution.
           Steps resume automatically when Redis returns.
```

## 24. Architecture Diagram

```mermaid
graph TB
    Client([Client / API Consumer])

    subgraph Atlas Platform
        IS[Identity Service<br/>:8081]
        WS[Workflow Service<br/>:8082]
        WK[Worker Service<br/>:8083]
        AS[Audit Service<br/>:8084]
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
    KF -->|audit.events| AS
    KF -->|domain.events| WS

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

## 25. Execution Lifecycle Diagram

```
                                    ATLAS EXECUTION LIFECYCLE
                                    ========================

   Client                Identity          Workflow Service              Kafka              Worker            Redis
     │                   Service                  │                       │                  │                 │
     │  POST /auth/login    │                     │                       │                  │                 │
     │─────────────────────>│                     │                       │                  │                 │
     │  JWT + refresh token │                     │                       │                  │                 │
     │<─────────────────────│                     │                       │                  │                 │
     │                      │                     │                       │                  │                 │
     │  POST /workflow-executions (JWT + idempotency key)                 │                  │                 │
     │───────────────────────────────────────────>│                       │                  │                 │
     │                      │                     │                       │                  │                 │
     │                      │              ┌──────┴──────┐                │                  │                 │
     │                      │              │ DB TRANSACTION│               │                  │                 │
     │                      │              │  execution    │               │                  │                 │
     │                      │              │  = PENDING    │               │                  │                 │
     │                      │              │  step[0]      │               │                  │                 │
     │                      │              │  = PENDING    │               │                  │                 │
     │                      │              │  outbox row   │               │                  │                 │
     │                      │              │  = written    │               │                  │                 │
     │                      │              └──────┬──────┘                │                  │                 │
     │  202 Accepted        │                     │                       │                  │                 │
     │<───────────────────────────────────────────│                       │                  │                 │
     │                      │                     │                       │                  │                 │
     │                      │              Outbox Poller (500ms)          │                  │                 │
     │                      │                     │  step.execute         │                  │                 │
     │                      │                     │──────────────────────>│                  │                 │
     │                      │                     │                       │  step.execute    │                 │
     │                      │                     │                       │─────────────────>│                 │
     │                      │                     │                       │                  │                 │
     │                      │                     │                       │                  │  SET lease NX   │
     │                      │                     │                       │                  │────────────────>│
     │                      │                     │                       │                  │  OK             │
     │                      │                     │                       │                  │<────────────────│
     │                      │                     │                       │                  │                 │
     │                      │                     │                       │                  │ ┌─────────────┐ │
     │                      │                     │                       │                  │ │ EXECUTE STEP│ │
     │                      │                     │                       │                  │ │ (heartbeat  │ │
     │                      │                     │                       │                  │ │  extends    │ │
     │                      │                     │                       │                  │ │  lease)     │ │
     │                      │                     │                       │                  │ └──────┬──────┘ │
     │                      │                     │                       │                  │        │        │
     │                      │                     │                       │  step.result     │        │        │
     │                      │                     │                       │  (SUCCEEDED)     │        │        │
     │                      │                     │                       │<─────────────────│        │        │
     │                      │                     │  step.result          │                  │ DEL lease       │
     │                      │                     │<──────────────────────│                  │────────────────>│
     │                      │                     │                       │                  │                 │
     │                      │              ┌──────┴──────┐                │                  │                 │
     │                      │              │ Deduplicate   │               │                  │                 │
     │                      │              │ Advance state │               │                  │                 │
     │                      │              │ Next step or  │               │                  │                 │
     │                      │              │ COMPLETED     │               │                  │                 │
     │                      │              └──────┬──────┘                │                  │                 │
     │                      │                     │  audit.event          │                  │                 │
     │                      │                     │──────────────────────>│                  │                 │
     │                      │                     │                       │                  │                 │
     │                      │                     │                       │        Audit Service consumes      │
     │                      │                     │                       │        and persists (idempotent)   │
```

## 26. Consistency Model

### Within a Single Service (Strong Consistency)

Each service uses PostgreSQL transactions for its own state. Within the Workflow Service:
- Execution state transitions are serializable — the state machine reads current state, validates the transition, and writes the new state in a single transaction.
- Outbox rows are written in the same transaction as the business state change. If the transaction fails, neither the state change nor the event is persisted. If it commits, both are guaranteed to exist.
- This means: **the Workflow Service database is always internally consistent.**

### Across Services (Eventual Consistency)

Services communicate asynchronously via Kafka. This means:
- When the Workflow Service writes a state change + outbox row, the Audit Service does not immediately see the event. It will eventually receive and persist it (within seconds under normal conditions, longer if Kafka is down).
- When the Identity Service publishes `role.permissions_changed`, other services' permission caches will be stale until they consume the event. Window: typically < 1 second.
- **There is no distributed transaction spanning multiple services.** Each service owns its consistency boundary.

### Delivery Semantics

- **Producers (outbox):** At-least-once. The outbox poller retries until Kafka confirms receipt. Duplicate publishes are possible during poller restarts.
- **Consumers:** At-least-once. Kafka consumer offsets are committed after processing. Rebalancing can cause re-delivery. All consumers are idempotent.
- **End-to-end:** At-least-once delivery with idempotent processing = effectively-once side effects.

### What This Means in Practice

- A client that starts a workflow and immediately queries its status may see `PENDING` before the first step has been dispatched (outbox poller hasn't run yet). This is expected and documented in the API response.
- An audit query immediately after a sensitive operation may not yet show the event. Eventually consistent — typically < 2 seconds.
- Two concurrent requests to start the same workflow (same idempotency key) will result in exactly one execution due to the database UNIQUE constraint. The second request receives a 409 Conflict.

## 27. Scaling Considerations

### Horizontal Scaling

| Component | Scaling Strategy | Bottleneck | Mitigation |
|-----------|-----------------|------------|------------|
| **Worker Service** | Add instances to Kafka consumer group. Kafka redistributes partitions automatically. | Partition count limits max parallelism | Create topics with enough partitions (default: 12 for `workflow.steps.execute`) |
| **Workflow Service** | Can run multiple instances behind a load balancer. Outbox poller must use leader election (DB advisory lock) to prevent duplicate publishing. | Outbox poller single-leader | Advisory lock is cheap; polling is fast. Not a bottleneck until thousands of events/second. |
| **Identity Service** | Stateless JWT validation. Scale horizontally behind load balancer. | Login endpoint (BCrypt is CPU-intensive) | Rate limiting + lockout prevents abuse. BCrypt cost 12 is ~250ms — acceptable. |
| **Audit Service** | Scale Kafka consumers. Append-only writes are fast. | Query performance on large tables | Partition `audit_events` by `occurred_at` (monthly). Add composite indexes. |

### Kafka Partition Strategy

| Topic | Partition Count | Rationale |
|-------|----------------|-----------|
| `workflow.steps.execute` | 12 | Allows up to 12 worker instances processing in parallel |
| `workflow.steps.result` | 6 | Lower volume than execute (results are 1:1 with commands) |
| `audit.events` | 6 | Sufficient for audit ingestion throughput |
| `domain.events` | 6 | General-purpose, low-medium volume |

Partitioning key: `tenant_id`. This guarantees ordering within a tenant (all steps for tenant A arrive at the same partition in order) while distributing load across partitions.

### Database Growth

- **`workflow_executions`** — Grows with execution volume. Add index on `(tenant_id, status)` for active execution queries. Archive completed executions older than 90 days to a `workflow_executions_archive` table (future enhancement).
- **`step_executions`** — Grows faster (multiple steps per execution). Index on `(execution_id, status)`. Same archive strategy.
- **`audit_events`** — Append-only, grows indefinitely. Partition by `occurred_at` (monthly range partitioning in PostgreSQL). Composite index on `(tenant_id, occurred_at)`.
- **`outbox`** — Self-cleaning (published rows deleted after 24h). Size stays bounded.

### Known Limits (v1)

- Single PostgreSQL instance = vertical scaling only for DB. Acceptable for v1. Evolution path: read replicas for query-heavy services (audit), or schema-per-tenant for isolation.
- Single Redis instance = no HA. If Redis goes down, workers can't acquire leases. Execution stalls but no data loss. Evolution path: Redis Sentinel or Redis Cluster.
- Outbox poller leader election means only one Workflow Service instance publishes events. Throughput ceiling: ~5,000 events/second with 500ms polling. Sufficient for v1.

## 28. Security Threat Model

| Threat | Attack Vector | Mitigation | Layer |
|--------|--------------|------------|-------|
| **JWT tampering** | Attacker modifies token payload (e.g., changes `tenant_id` or `roles`) | HS256 signature validation on every request. Tampered tokens fail verification and are rejected with 401. | All services |
| **Replay attacks** | Attacker captures and re-sends a valid JWT | Short TTL (15 minutes) limits window. Refresh tokens are single-use (rotated on each refresh). Revoked tokens tracked in `refresh_tokens` table. | Identity Service |
| **Tenant data escape** | Authenticated user attempts to access another tenant's data | Triple enforcement: JWT `tenant_id` validated by `TenantScopeFilter`, Hibernate `@Filter` on all entities, explicit `WHERE tenant_id` in all repository queries. All three must be bypassed simultaneously. | All services |
| **Brute force login** | Attacker tries many passwords against a known email | Account lockout after 5 consecutive failures for 15 minutes. Failed attempts tracked per user. `atlas.auth.login.failure` metric + alert. | Identity Service |
| **Privilege escalation** | User attempts to perform actions beyond their role | `@RequiresPermission` annotation on every endpoint. Permission resolution from role cache — not from user input. 403 on unauthorized access. | All services |
| **Event injection** | Attacker publishes malicious events directly to Kafka | Kafka is internal-only (not exposed to external network). Services validate event payloads before processing. Malformed events are dead-lettered. | Kafka consumers |
| **Audit log tampering** | Attacker attempts to modify or delete audit records | Audit table is append-only. No UPDATE or DELETE operations exposed via API or service code. DB user for Audit Service has INSERT + SELECT only (no UPDATE/DELETE grants). | Audit Service |
| **Credential exposure** | Secrets (JWT signing key, DB password) leaked in code or logs | Secrets injected via environment variables, never hardcoded. Structured logging excludes sensitive fields. Passwords hashed with BCrypt, refresh tokens hashed with SHA-256. | All services |

## 29. Configuration Strategy

### Spring Profiles

| Profile | Purpose | Activated By |
|---------|---------|-------------|
| `local` | Docker Compose development. Swagger UI enabled, debug logging, seed data loaded. | `SPRING_PROFILES_ACTIVE=local` |
| `test` | Testcontainers integration tests. Ephemeral databases, in-memory overrides. | Activated automatically in test classpath. |
| `prod` | Production-like defaults. Swagger UI disabled, INFO logging, no seed data. | Default when no profile is specified. |

### Externalized Configuration

All environment-specific values are injected via environment variables. Nothing environment-specific is hardcoded.

| Variable | Service | Purpose | Default |
|----------|---------|---------|---------|
| `ATLAS_DB_URL` | All with DB | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/atlas` |
| `ATLAS_DB_USERNAME` | All with DB | Database user | `atlas` |
| `ATLAS_DB_PASSWORD` | All with DB | Database password | (none — must be set) |
| `ATLAS_KAFKA_BOOTSTRAP` | All | Kafka bootstrap servers | `localhost:9092` |
| `ATLAS_REDIS_HOST` | Worker, Workflow | Redis host | `localhost` |
| `ATLAS_REDIS_PORT` | Worker, Workflow | Redis port | `6379` |
| `ATLAS_JWT_SECRET` | All | HS256 signing key | (none — must be set) |
| `ATLAS_JWT_EXPIRATION_MINUTES` | Identity | Access token TTL | `15` |
| `ATLAS_REFRESH_EXPIRATION_DAYS` | Identity | Refresh token TTL | `7` |
| `ATLAS_OUTBOX_POLL_INTERVAL_MS` | Identity, Workflow | Outbox poller frequency | `500` |
| `ATLAS_TIMEOUT_CHECK_INTERVAL_S` | Workflow | Timeout detector frequency | `10` |
| `ATLAS_WORKER_CONCURRENCY` | Worker | Kafka listener threads | `4` |
| `ATLAS_WORKER_DRAIN_TIMEOUT_S` | Worker | Graceful shutdown drain time | `30` |

Docker Compose injects these via `.env` file with sensible local defaults.

## 30. Operational Runbook

Quick-reference for diagnosing and resolving common operational issues.

### Dead-Letter Queue Depth Increasing

```
Symptom:  atlas.workflow.deadletter.count rising on Grafana
          Alert: "Dead-letter queue depth increasing"

Diagnose:
  1. GET /api/v1/dead-letter → inspect failing items
  2. Check error_detail — is it one step type or many?
  3. Check if the underlying cause is resolved (external service down, bad config)

Resolve:
  - If transient: fix root cause, then POST /api/v1/dead-letter/{id}/replay for each item
  - If permanent: investigate step configuration, fix definition, publish new version
```

### Kafka Consumer Lag Growing

```
Symptom:  atlas.kafka.consumer.lag rising on Grafana
          Alert: "Kafka consumer lag > threshold for 5 minutes"

Diagnose:
  1. Which consumer group is lagging? (workflow-service, worker-service, audit-service)
  2. Check service health: GET /actuator/health
  3. Check CPU/memory — is the service overwhelmed?

Resolve:
  - Worker lag: scale worker instances (add containers)
  - Workflow lag: check for slow DB queries, connection pool exhaustion
  - Audit lag: check audit_events table size, add indexes if queries are slow
```

### Outbox Rows Accumulating

```
Symptom:  Unpublished outbox rows growing (custom metric or DB query)
          Alert: "Outbox unpublished rows growing"

Diagnose:
  1. Is Kafka reachable? Check Kafka container health.
  2. Is the outbox poller running? Check service logs for poller activity.
  3. Is leader election working? (Only one instance should poll)

Resolve:
  - Kafka down: restore Kafka. Outbox will drain automatically.
  - Poller stuck: restart the service. Advisory lock will be re-acquired.
  - Leader election issue: check DB advisory lock status.
```

### Redis Unavailable

```
Symptom:  Workers not executing steps. atlas.worker.lease.acquired drops to zero.
          Executions stalling — steps stuck in LEASED/RUNNING past timeout.

Diagnose:
  1. Check Redis container: docker compose ps redis
  2. Check worker logs for Redis connection errors

Resolve:
  - Restore Redis. Workers will resume acquiring leases immediately.
  - No data loss — timeout detector will re-publish stalled steps.
  - Delay steps using Redis ZADD will resume once Redis returns.
```

### Execution Stuck in COMPENSATING

```
Symptom:  Execution in COMPENSATING state for longer than expected.
          Or: execution in COMPENSATION_FAILED (terminal).

Diagnose:
  1. GET /api/v1/workflow-executions/{id}/timeline → find which compensation step is failing
  2. Check dead-letter for the compensation step
  3. Check error detail — is the compensation target available?

Resolve:
  - COMPENSATING + step retrying: wait for retries to complete
  - COMPENSATION_FAILED: fix root cause, replay dead-letter item
  - Manual intervention may be required for unrecoverable compensation failures
```

### High Auth Failure Rate

```
Symptom:  atlas.auth.login.failure spiking on Grafana
          Alert: "Auth failure rate elevated"

Diagnose:
  1. Check audit trail: GET /api/v1/audit-events?event_type=auth.login_failed
  2. Is it one user (forgotten password) or many IPs (brute force)?
  3. Check locked accounts

Resolve:
  - Single user: unlock account after identity verification
  - Brute force pattern: verify lockout is working (5 failures → 15min lock)
  - Consider adding IP-level rate limiting (Tier 2 feature)
```

## 31. Future Evolution Roadmap

What Atlas v2+ could look like. These are not planned — they are documented evolution paths that show the architecture was designed with growth in mind.

| Evolution | What It Enables | What Changes |
|-----------|----------------|-------------|
| **Workflow branching / DAG execution** | Conditional paths, parallel step execution, fan-out/fan-in | Execution engine evolves from linear list to DAG traversal; step_executions gains `depends_on` column |
| **RS256 asymmetric JWT signing** | Services validate tokens without sharing a secret; supports external OIDC providers | Identity Service holds private key, other services hold public key; key rotation via JWKS endpoint |
| **Schema-per-tenant / DB-per-tenant** | Stronger tenant isolation, per-tenant backup/restore, compliance requirements | Tenant-aware connection routing; Flyway runs per-tenant; more operational complexity |
| **Custom step type plugins** | Tenants register their own step executors (webhooks, custom scripts) | Plugin registry in Workflow Service; Worker loads executors dynamically; sandboxing required |
| **Admin console (React)** | Visual execution timeline, dead-letter management, tenant dashboard | Read-only UI consuming existing REST APIs; no backend changes needed |
| **Multi-region** | Geographic redundancy, lower latency for global tenants | Kafka MirrorMaker, conflict resolution strategy, region-aware routing |
| **gRPC internal communication** | Higher throughput for hot paths (step result reporting) | Add gRPC endpoint to Workflow Service alongside REST; Worker uses gRPC client |
| **Per-tenant quotas and rate limiting** | Prevent noisy-neighbor; enforce SLA tiers | Redis-backed token bucket per tenant; quota policy on tenant entity |

## 32. Infrastructure Cost Awareness

Real platform engineers think about cost, not just architecture. These are the cost tradeoffs behind Atlas's infrastructure choices.

### Why Kafka Over Cheaper Alternatives

| Alternative | Why Not |
|-------------|---------|
| **RabbitMQ** | Simpler and cheaper for low-throughput. But: no persistent log replay, no consumer group rebalancing, weaker at high-throughput fan-out. Atlas needs replay for dead-letter recovery and multiple consumer groups (workers, audit, domain listeners) on the same event stream. |
| **SQS/SNS** | Managed = zero ops cost. But: Atlas is self-hosted (Docker Compose). Adding AWS dependency defeats the one-command-local-setup goal. Kafka KRaft is operationally simple enough for a single-cluster deployment. |
| **Database polling** | Zero additional infra. But: polling creates DB load proportional to poll frequency x consumer count. At 4 consumer types x 500ms polling = constant query pressure. Kafka eliminates this entirely — consumers are push-based with zero DB cost. |

**The real cost of Kafka** is operational complexity (topic management, partition tuning, monitoring lag). Atlas mitigates this by: KRaft mode (no Zookeeper), pre-created topics via scripts, and consumer lag as a first-class metric on the Platform Health dashboard.

### Why Redis Over Pure DB for Leases

| Approach | Cost Profile |
|----------|-------------|
| **Redis leases** | Adds one infrastructure component. But: `SET NX EX` is O(1) with sub-millisecond latency. Lease operations never touch the database, keeping DB connection pool free for business queries. |
| **DB advisory locks** | Zero additional infra. But: each lease acquire/release is a DB round-trip. Under high worker concurrency, this competes with execution state writes for connection pool and I/O bandwidth. |
| **DB `SELECT FOR UPDATE SKIP LOCKED`** | No additional infra. But: row-level locks create contention on the step_executions table. Workers polling for available steps add read pressure that scales linearly with worker count. |

Redis is justified because lease operations are high-frequency (acquire + heartbeat every N seconds per active step) and must not compete with the Workflow Service's critical path (state transitions + outbox writes).

### Cost-Conscious Design Decisions

- **Shared PostgreSQL** — One DB instance instead of four. Saves ~75% on DB compute for local dev and small deployments. Schema separation maintains logical isolation.
- **Outbox cleanup (24h)** — Prevents outbox table from growing unbounded. Without this, storage costs grow linearly with event volume forever.
- **Audit table partitioning** — Monthly range partitions allow dropping old partitions cheaply instead of running expensive `DELETE` queries.
- **Worker statelessness** — Workers have no database. Adding worker capacity costs only compute, not storage. The cheapest component to scale.
- **Single Redis instance** — No Sentinel/Cluster overhead for v1. If Redis dies, the system stalls but doesn't corrupt. The cost of HA Redis is not justified until uptime SLAs require it.
