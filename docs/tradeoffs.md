# Atlas Engineering Tradeoffs

This document records the seven major engineering tradeoffs made in Atlas v1. Each entry describes the decision, what was rejected, and the rationale. These are documented here because the reasoning behind a design matters as much as the design itself.

---

## 1. At-least-once delivery over exactly-once

**Decision:** Atlas uses at-least-once message delivery with idempotent handlers.

**Rejected:** Exactly-once semantics via distributed transactions or Kafka transactions.

**Rationale:** Exactly-once delivery requires either distributed transactions (two-phase commit across the broker and the database) or careful use of Kafka's transactional API, both of which add substantial complexity. At-least-once delivery combined with idempotent consumers achieves effectively-once semantics at far lower cost:

- Step results are deduplicated by `step_execution_id` + `attempt_count` before advancing state. A duplicate delivery changes nothing.
- Audit events use `INSERT ... ON CONFLICT DO NOTHING` on `audit_event_id`. A duplicate write is silently discarded.
- The outbox pattern ensures no event is lost, even if Kafka is temporarily unavailable.

The complexity of exactly-once is disproportionate to its benefit in a system that can be made correct through idempotency at the consumer side.

---

## 2. Orchestration over choreography

**Decision:** The Workflow Service is the central coordinator of all execution state. Workers are commanded, not autonomous.

**Rejected:** Choreography, where each service reacts to events independently without a central coordinator.

**Rationale:** Choreography distributes execution logic across services, which has three significant operational costs:

- **Debugging:** Reconstructing what happened requires correlating events from multiple services. There is no single place to query "what is the current state of this execution."
- **Compensation:** Each service must know its own compensation trigger conditions and coordinate with others. There is no single place to enforce reverse ordering. Partial compensation failures become difficult to detect and recover from.
- **Timeline:** Building an execution timeline requires event sourcing across all services rather than querying a single execution table.

Orchestration trades some coupling (workers depend on Workflow Service commands) for significant operational simplicity. Execution state is always reconstructable from one service's database.

---

## 3. Shared database with tenant scoping over database-per-tenant

**Decision:** A single PostgreSQL instance with separate schemas per service and `tenant_id` scoping on all tenant-owned rows.

**Rejected:** Schema-per-tenant or database-per-tenant isolation models.

**Rationale:** Database-per-tenant provides the strongest isolation but multiplies operational overhead proportionally with tenant count. Schema-per-tenant is a middle ground but complicates Flyway migrations (migrations must run per schema) and makes cross-tenant reporting impossible.

For v1, shared database with robust application-level enforcement (Hibernate `@Filter`, repository-level WHERE clauses, `TenantScopeFilter` on every request) provides sufficient isolation. The three-layer enforcement means no single code path failure exposes cross-tenant data.

The architecture is designed to evolve: the `tenant_id` column is already present on every row. Migration to schema-per-tenant is a change to the repository layer and Flyway configuration, not a data model redesign.

---

## 4. Redis for leases and delay scheduling

**Decision:** Worker lease acquisition uses Redis `SET NX EX`. Delay step scheduling uses Redis sorted sets (`ZADD`).

**Rejected:** Database-backed leases, or skipping distributed leases altogether.

**Rationale:** Redis is appropriate because both use cases are inherently ephemeral:

- **Leases:** A lease must expire automatically if the worker crashes. Redis TTL provides this for free. A lost lease means the Workflow Service timeout detector re-publishes the step command — no data loss, just a retry. The lease exists solely to prevent two workers from executing the same step simultaneously.
- **Delay scheduling:** A delay wake-up timestamp need not survive Redis restarts. If Redis goes down and loses the sorted set entry, the Workflow Service timeout detector will eventually re-publish the step anyway (it treats an overdue step as a timeout).

The consequence of Redis unavailability is delayed execution, not data loss or corruption. This is an acceptable tradeoff given Redis's high availability characteristics and the recovery path via the timeout detector.

---

## 5. Compensation is best-effort but durable

**Decision:** Compensation steps are executed with retries. If all retries are exhausted, the execution enters `COMPENSATION_FAILED`, a terminal state requiring operator intervention. The failed step is dead-lettered.

**Rejected:** Treating compensation failure as unrecoverable without operator visibility, or blocking indefinitely on compensation.

**Rationale:** Compensation cannot be guaranteed to succeed. A "refund-payment" step can fail just as a "charge-payment" step can fail. Pretending otherwise leads to silent inconsistencies.

The design makes compensation failures visible and actionable:
- `COMPENSATION_FAILED` is a distinct terminal state, not conflated with `FAILED`.
- The failed compensation step is placed in the dead-letter queue with full error history and step payload.
- An alert fires when dead-letter queue depth increases.
- Operators can inspect the dead-letter item and replay it once the underlying cause is resolved.

This is a deliberate choice to surface operational problems rather than hide them. A system that fails loudly and visibly is easier to operate than one that silently corrupts state.

---

## 6. Four services, not more

**Decision:** Atlas is composed of exactly four services: Identity, Workflow, Worker, and Audit.

**Rejected:** Further decomposition, such as a separate Definition Service, a Compensation Service, or a Notification Service.

**Rationale:** Each service in Atlas has real, non-trivial complexity:
- Identity handles multi-tenant auth, JWT lifecycle, RBAC, and permission cache distribution.
- Workflow owns the execution state machine, saga orchestration, dead-letter handling, and multiple background schedulers.
- Worker manages distributed lease acquisition, concurrent step execution, heartbeating, and graceful shutdown.
- Audit provides append-only ingestion with idempotency and queryable history.

Further decomposition would produce services with insufficient depth to demonstrate production-grade implementation. The goal is to demonstrate ownership of systems with real complexity, not to maximize service count.

Four well-implemented services with full observability, proper failure handling, and documented tradeoffs are more credible than eight thin services.

---

## 7. HS256 over RS256 for JWT signing in v1

**Decision:** JWT tokens are signed with HS256 (shared symmetric secret). All services share the signing key to validate tokens locally.

**Rejected:** RS256 (asymmetric signing) where only Identity Service holds the private key and other services use a published public key.

**Rationale:** RS256 is the correct choice when the token issuer and token consumers cannot share secrets securely, or when the service count is large enough that rotating a shared secret becomes operationally costly.

For v1, the signing key is a single config value managed alongside other service secrets. All four services already share infrastructure secrets (database credentials, Kafka credentials). Adding one more shared secret has negligible operational cost.

The architecture supports migration to RS256 without contract changes: the JWT structure, validation logic, and RBAC model are unchanged. Switching to RS256 requires replacing the signing key configuration with a key pair, updating `JwtTokenParser` to use the public key for validation, and publishing the public key on a well-known endpoint. This is a v2 enhancement with a clear path.
