# Building Atlas: The Architecture Decisions That Shaped a Distributed Workflow Engine

I spent the last several months building Atlas — a multi-tenant distributed workflow orchestration platform. It's four services, a shared PostgreSQL instance, Kafka, and Redis. Not revolutionary technology choices, but the interesting part is always in the decisions between the obvious options.

This post is a walkthrough of the non-obvious choices. I want to explain the reasoning, be honest about what I'd do differently, and give you enough specifics that you can evaluate whether any of it applies to your own systems.

---

## What Atlas Is and Why It Built It

Atlas orchestrates durable workflows. You define a workflow as a sequence of steps — HTTP calls, internal commands, delays, event waits — and Atlas ensures they execute reliably, retry on failure, and compensate in reverse order if something goes wrong. Every step result is durable. Every state transition is audited. The whole thing is multi-tenant from day one.

The practical use case is any system that needs saga-style orchestration without wanting to bolt it onto an existing service. Order processing, provisioning pipelines, approval flows — anything where "fire and forget" isn't acceptable and you want a recoverable state machine rather than distributed hope.

I built it as a portfolio project to demonstrate production-grade distributed systems engineering. The goal was four well-implemented services with real complexity, proper failure handling, and documented tradeoffs — not eight thin services that each do twelve lines of work.

---

## Why Orchestration Over Choreography

The first real decision was how to coordinate work across services. Choreography — where each service listens for events and reacts independently — is appealing because it's decoupled. No single coordinator. Services don't know about each other.

I rejected it for Atlas, and I'd make the same call again.

The problem with choreography isn't the happy path. It's what happens when something fails. In a choreographed saga, compensation logic is spread across every participating service. If a payment service, an inventory service, and a shipping service all react to an "order placed" event, and the shipping step fails after the other two have committed, you now need each service to independently recognize that it needs to compensate, in the right order, triggered by the right failure event.

That's three places to get the compensation logic right. Three places where a bug means a partial rollback. And when it goes wrong in production, you're correlating events across three separate Kafka topics and three separate databases to understand what happened.

Orchestration concentrates that complexity. The Workflow Service is the only thing that knows the full execution plan. It drives workers via commands, not events. When step three fails, the Workflow Service knows to compensate step two before step one, in that order, because it has the full definition and the full execution history in one place.

The cost is coupling — workers depend on the Workflow Service command protocol. That's a real tradeoff. But for a saga engine, the benefit of having all execution state in one database, queryable with a single JOIN, is worth it.

---

## The Outbox Pattern — Why Not Just Publish to Kafka Directly

This one took me longer to appreciate when I first encountered it. The problem is this: you're in a database transaction. You've updated the execution state from PENDING to RUNNING. Now you want to publish a `step.execute` command to Kafka so the worker picks it up. What do you do?

Option A: publish to Kafka inside the transaction. The transaction commits, Kafka gets the message. Except — what if the Kafka publish succeeds but the transaction then fails to commit? You've published an event for a state change that never happened. A worker starts executing a step that doesn't exist in RUNNING state.

Option B: publish to Kafka after the transaction commits. The transaction commits, then you publish. Except — what if the process crashes between the commit and the publish? The execution is now RUNNING in the database but no worker ever gets the command. It's stuck.

Neither option is correct. The outbox pattern solves this by making the Kafka publish a side effect of the database transaction itself. Within the same transaction that advances the execution state, you insert an outbox row. Outbox row and state change commit atomically, or both roll back. A background poller (every 500ms in Atlas) picks up unpublished outbox rows and sends them to Kafka. The poller marks them published only after the send succeeds.

```java
@Scheduled(fixedDelay = 500)
@Transactional
public void publishPending() {
    List<OutboxEvent> batch = outboxRepository.findUnpublishedBatch(PageRequest.of(0, BATCH_SIZE));
    for (OutboxEvent event : batch) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload()).get();
            event.markPublished();
            outboxRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to publish outbox event {}: {}", event.getId(), e.getMessage(), e);
            break; // preserve ordering
        }
    }
}
```

The consequence is at-least-once delivery: if the poller sends the message but crashes before marking it published, it'll send it again on restart. So consumers must be idempotent. Atlas handles this by deduplicating step results on `step_execution_id + attempt_count` before advancing state. A duplicate delivery changes nothing. That's a simpler and more reliable guarantee than trying to get exactly-once delivery from the infrastructure.

The tradeoff that trips people up: the outbox poller adds latency. In Atlas it's up to 500ms before a step command reaches Kafka. For a workflow engine this is fine — workflows are not latency-sensitive. If you need sub-100ms propagation, the outbox pattern in its polling form isn't right for you. Alternatives include CDC (Change Data Capture) via Debezium, which can reduce latency significantly but adds infrastructure complexity.

---

## State Machine Design — Explicit Transitions Prevent Bugs

Every workflow execution in Atlas has a status: PENDING, RUNNING, WAITING, FAILED, COMPENSATING, COMPENSATED, COMPENSATION_FAILED, CANCELED, TIMED_OUT, COMPLETED. Same story for step executions.

The naive implementation lets you set any status from anywhere. The problem is that eventually someone writes code that transitions COMPENSATED back to RUNNING because they forgot to check the guard condition, or COMPLETED to FAILED because an error handler is too broad. These bugs are hard to detect until they cause real corruption.

Atlas uses a static state machine that encodes every valid transition at startup:

```java
static {
    ALLOWED_TRANSITIONS.put(ExecutionStatus.PENDING,
            EnumSet.of(ExecutionStatus.RUNNING, ExecutionStatus.CANCELED));
    ALLOWED_TRANSITIONS.put(ExecutionStatus.RUNNING,
            EnumSet.of(ExecutionStatus.WAITING, ExecutionStatus.COMPLETED,
                       ExecutionStatus.FAILED, ExecutionStatus.CANCELED,
                       ExecutionStatus.TIMED_OUT));
    // ...
    ALLOWED_TRANSITIONS.put(ExecutionStatus.COMPLETED, EnumSet.noneOf(ExecutionStatus.class));
}
```

`ExecutionStateMachine.validateTransition(from, to)` throws `IllegalStateException` on an invalid transition. It's called everywhere a status change happens. The result is that a misrouted result handler or a bug in compensation logic fails loudly and immediately instead of silently corrupting state.

The subtlety I want to call out: terminal states have empty allowed-transition sets. COMPLETED, COMPENSATED, COMPENSATION_FAILED, CANCELED, TIMED_OUT — none of them have valid successors. That means any code that tries to re-process a completed execution will blow up at the state machine before it touches the database. This is the right behavior. Idempotent processing is good, but idempotent processing should be enforced by checking whether you've already processed something, not by silently accepting any input on a completed record.

---

## Multi-Tenancy With Hibernate Filters — The Double-Guard Approach

Atlas is multi-tenant. Every table has a `tenant_id` column, and no tenant should ever be able to see another tenant's data. The question is: how do you enforce that?

The obvious answer is "add `WHERE tenant_id = ?` to every query." And Atlas does that. But "add it everywhere" has a failure mode: someone writes a new query and forgets the WHERE clause. It passes code review. It goes to production. You have a data leak.

Atlas adds a second enforcement layer using Hibernate's `@Filter` mechanism. Each entity is annotated:

```java
@Entity
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class WorkflowExecution { ... }
```

An AOP aspect activates this filter before every repository method:

```java
@Before("execution(* com.atlas.workflow.repository.*.*(..))")
public void enableTenantFilter() {
    if (tenantContext.isSet()) {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter("tenantFilter").setParameter("tenantId", tenantContext.getTenantId());
    }
}
```

The result is a double guard: the explicit WHERE clause in the repository method, and the Hibernate filter applied at the session level. A new query that forgets the WHERE clause still has the filter as a fallback. Both layers would have to fail simultaneously for a cross-tenant leak to occur.

There's a deliberate asymmetry I want to acknowledge: the filter is a safety net, not the primary enforcement. I don't want engineers to rely on the filter and skip the explicit WHERE clause. The filter is there for the cases where something slips through. The correct mental model is defense in depth, not "the aspect handles it."

The filter is also bypassed for scheduled tasks that run outside a request scope (the aspect catches `ScopeNotActiveException` and skips). That's correct — the timeout detector and outbox poller legitimately need to query across tenants. But it means those code paths need extra care, because the safety net isn't there.

---

## Compensation as a First-Class Concept

Most workflow engines treat failure as "retry until it works or give up." Atlas treats failure differently: if a workflow fails after several steps have succeeded, those steps need to be undone. The payment needs to be refunded. The inventory needs to be released.

The key insight is that compensation cannot be guaranteed to succeed. A compensation step is just another HTTP call or command handler — it can fail for all the same reasons the original step failed. Pretending otherwise leads to silent inconsistencies. I've seen systems that retry compensation indefinitely and never surface the failure to operators. That's worse than an honest failure.

Atlas models compensation failure as a distinct terminal state: `COMPENSATION_FAILED`. It's not conflated with `FAILED`. When compensation exhausts its retries, the failed step goes to the dead-letter queue with full error history and the execution enters `COMPENSATION_FAILED`. An alert fires. An operator can inspect the dead-letter item and replay it once the underlying problem is fixed.

This might sound like a cop-out — "we just raise an alert and let humans fix it." But the alternative (hiding the failure, retrying forever, or silently moving on) is worse. Distributed systems fail. The goal is to fail loudly and give operators the information they need to recover. A system that surfaces failures clearly is vastly easier to operate than one that doesn't.

---

## What I'd Do Differently / Lessons Learned

**The outbox poller needs leader election.** Right now, if you run two instances of the Workflow Service, both pollers will try to publish the same outbox rows. The `markPublished()` call prevents double-delivery most of the time (the second poller will see the row as already published), but there's a race window. In production, you want exactly one poller running at a time per outbox. The right fix is a distributed lock via Redis or a dedicated leader election library. I deferred this because it adds complexity and the demo runs a single instance, but I wouldn't ship to production without it.

**HS256 is a liability at scale.** All four services share the JWT signing secret to validate tokens locally. That's fine for four services in a controlled environment. If Atlas grew to twenty services, rotating the secret would require coordinating a deployment across all of them simultaneously. RS256 solves this: the Identity Service holds the private key, every other service validates using the published public key at a `/.well-known/jwks.json` endpoint. No rotation coordination needed. This is on the roadmap.

**The permission cache invalidation is eventually consistent.** Services bootstrap their permission cache from Identity at startup and update it via Kafka `role.permissions_changed` events. If Kafka has a hiccup, a permission change might not propagate for several minutes. I accepted this as a v1 tradeoff — permissions rarely change, and the window is bounded by message delivery guarantees. But if I were building this for a security-sensitive enterprise product, I'd want a lower-latency invalidation path, probably a push mechanism rather than pull.

**Testcontainers saved me repeatedly.** Every integration test in Atlas spins up real PostgreSQL, real Kafka, real Redis. Not mocks. Not in-memory databases. The tests are slower but they catch real integration issues — timing bugs, Kafka offset behavior, Flyway migration ordering — that mocks would have hidden. If you're not using Testcontainers for integration tests, you're writing tests that only prove your code works against fake infrastructure.

**Document your tradeoffs while you're making them.** I have a `tradeoffs.md` in the docs directory that records seven major decisions with the rejected alternatives and rationale. Writing this while building meant I could articulate the reasoning clearly. Trying to reconstruct it six months later would have been much harder. Engineering decisions have a half-life on your mental model of the system.

---

## Conclusion

Atlas is about 8,000 lines of production-quality Java across four services. The technology choices are boring on purpose: Spring Boot, PostgreSQL, Kafka, Redis. Boring technology is predictable technology. The interesting part is in the details — how you handle the dual-write problem, how you enforce multi-tenancy without relying on a single guard, how you make compensation failures visible rather than hidden.

The tradeoffs I'd revisit are the outbox poller without leader election and the HS256 signing key. Both are acceptable for a controlled demo environment but would need to change before running real traffic. Everything else I'd do again.

If any of this is interesting to you, the full codebase is at [GitHub] and the architecture decisions are documented in `docs/tradeoffs.md`. Questions welcome.
