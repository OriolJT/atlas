# Plan 7: Chaos Testing Suite

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan.

**Goal:** Build a comprehensive chaos testing suite that proves Atlas's system guarantees hold under failure: duplicate messages are deduplicated, retries work, compensation executes in reverse order, dead-letter captures exhausted steps, and the outbox survives Kafka outages.

**Architecture:** Integration tests in the workflow-service using Testcontainers. Each test sets up a scenario, injects a failure, and asserts the system recovers to the correct state. Uses the existing InternalCommandExecutor's failure injection (TRANSIENT/PERMANENT) to simulate step failures.

**Tech Stack:** JUnit 5, Testcontainers 2.0, Awaitility, Spring Boot Test

**Depends on:** Plans 1-6 (all services built)

---

## System Guarantees Under Test

These tests directly verify the guarantees from the Atlas design spec (sections 22–23):

| Guarantee | Test |
|-----------|------|
| Step executed **at least once, never zero times** | Test 1: retry until success |
| Step result processed **at most once per attempt** | Test 3: duplicate result ignored |
| Compensation runs **only for completed steps, in reverse order** | Test 2 & 7: PERMANENT failure triggers reverse compensation |
| Idempotency key produces **exactly one execution** | Test 5: duplicate start request |
| Dead-letter captures **exhausted retries** | Test 4: PERMANENT failure, max_attempts=2 |
| Cancellation **stops further step execution** | Test 6: cancel mid-run |
| Outbox survives **Kafka unavailability** (verified structurally) | Test 3 indirectly; outbox row existence checked pre-Kafka |

---

## File Structure

```
workflow-service/src/test/java/com/atlas/workflow/chaos/
  RetryAndRecoveryTest.java       (Tasks 1, 4 — Test 1 and Test 4)
  CompensationChaosTest.java      (Tasks 2, 7 — Test 2 and Test 7)
  DeduplicationTest.java          (Task 3 — Test 3)
  IdempotencyTest.java            (Task 5 — Test 5)
  CancellationTest.java           (Task 6 — Test 6)
```

Total: **5 test classes**, **7 test methods**, **7 tasks**.

---

## Shared Test Infrastructure

Before implementing the individual test classes, establish shared helpers used by all chaos tests.

### Base class / shared setup

All chaos test classes use `@SpringBootTest` with the existing `TestcontainersConfiguration` (PostgreSQL 17 + Confluent Kafka 7.6 + Redis 7). Import it via `@Import(TestcontainersConfiguration.class)`.

```java
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
```

### Failure injection model

The `InternalCommandExecutor` accepts a `failure_config` field in the step input payload:

```json
{
  "failure_config": {
    "failure_type": "TRANSIENT",   // or "PERMANENT"
    "fail_for_attempts": 2          // only for TRANSIENT: succeed on attempt N+1
  }
}
```

- `TRANSIENT` — the executor throws a retryable exception for the first `fail_for_attempts` attempts, then succeeds.
- `PERMANENT` — the executor throws a non-retryable exception on every attempt.

### Worker simulation (Option B — direct invocation)

No external worker runs during tests. After the workflow service publishes a `step.execute` outbox command, tests simulate the worker by calling `StepResultProcessor.process()` directly with a synthetic `StepResultEvent`. This keeps tests deterministic and fast.

Helper method (extract to `ChaosTestHelper` or as a `@TestComponent`):

```java
void simulateWorkerSuccess(UUID stepExecutionId, int attemptCount) {
    StepResultEvent result = StepResultEvent.builder()
        .stepExecutionId(stepExecutionId)
        .attemptCount(attemptCount)
        .status(StepStatus.SUCCEEDED)
        .output(Map.of())
        .build();
    stepResultProcessor.process(result);
}

void simulateWorkerFailure(UUID stepExecutionId, int attemptCount, boolean retryable) {
    StepResultEvent result = StepResultEvent.builder()
        .stepExecutionId(stepExecutionId)
        .attemptCount(attemptCount)
        .status(StepStatus.FAILED)
        .errorCode(retryable ? "TRANSIENT_ERROR" : "PERMANENT_ERROR")
        .retryable(retryable)
        .build();
    stepResultProcessor.process(result);
}
```

### Awaitility polling

For any async state change driven by schedulers (timeout detector, retry scheduler), use Awaitility:

```java
await().atMost(Duration.ofSeconds(10))
       .pollInterval(Duration.ofMillis(200))
       .until(() -> executionRepository.findById(execId)
                        .map(e -> e.getStatus() == ExecutionStatus.COMPLETED)
                        .orElse(false));
```

---

## Tasks

### Task 1 — `RetryAndRecoveryTest.java`: Test 1 — Step executes at least once after retry

**File:** `workflow-service/src/test/java/com/atlas/workflow/chaos/RetryAndRecoveryTest.java`

**What to implement:**

```java
@Test
void stepExecutesAtLeastOnceAfterTransientFailure() {
    // 1. Register a workflow definition with 1 step, retry policy max_attempts=5
    // 2. Start an execution with failure_config: TRANSIENT, fail_for_attempts=2
    // 3. Simulate worker: fail attempt 1 (retryable), fail attempt 2 (retryable),
    //    succeed attempt 3
    //    For each attempt: call simulateWorkerFailure / simulateWorkerSuccess
    //    After each failure result, use Awaitility to wait for RETRY_SCHEDULED state
    // 4. After success, wait for execution COMPLETED
    // 5. Assertions:
    //    - execution.status == COMPLETED
    //    - stepExecution.attemptCount == 3
    //    - stepExecution.stateHistory contains at least 2 entries with status RETRY_SCHEDULED
}
```

**Key classes to autowire:** `WorkflowDefinitionService`, `WorkflowExecutionService`, `StepResultProcessor`, `WorkflowExecutionRepository`, `StepExecutionRepository`

**Design spec reference:** Scenario 1 (Worker Crashes Mid-Step) — observable: `attempt_count=2`; Guarantee: "at least once, never zero times"

---

### Task 2 — `RetryAndRecoveryTest.java`: Test 4 — Dead-letter captures exhausted retries

**File:** Same class as Task 1 (`RetryAndRecoveryTest.java`)

**What to implement:**

```java
@Test
void permanentFailureDeadLettersStep() {
    // 1. Register workflow definition with 1 step, retry policy max_attempts=2
    // 2. Start execution with failure_config: PERMANENT
    // 3. Simulate worker: fail attempt 1 (non-retryable permanent)
    //    — since PERMANENT, no retry; step goes straight to DEAD_LETTERED
    //    OR: simulate 2 failures to exhaust max_attempts
    //    (follow whichever model InternalCommandExecutor uses for PERMANENT)
    // 4. Wait for execution to reach FAILED or COMPENSATED
    // 5. Assertions:
    //    - execution.status is FAILED or COMPENSATED (terminal)
    //    - stepExecution.status == DEAD_LETTERED
    //    - dead_letter_items table has 1 row for this stepExecutionId
    //    - dead letter item has errorCode and full context
}
```

**Key classes:** same as Task 1, plus `DeadLetterRepository`

**Design spec reference:** Scenario 5; Guarantee: "dead-letter captures exhausted retries"

---

### Task 3 — `CompensationChaosTest.java`: Test 2 — Compensation runs in reverse order

**File:** `workflow-service/src/test/java/com/atlas/workflow/chaos/CompensationChaosTest.java`

**What to implement:**

```java
@Test
void compensationRunsInReverseOrderOnPermanentFailure() {
    // 1. Register workflow definition with 3 steps:
    //    step-0: no compensation defined
    //    step-1: has compensation step "comp-1"
    //    step-2: PERMANENT failure, has compensation step "comp-2"
    // 2. Start execution
    // 3. Simulate worker:
    //    - step-0 SUCCEEDS
    //    - step-1 SUCCEEDS
    //    - step-2 FAILS (permanent/non-retryable)
    // 4. Execution enters COMPENSATING state
    // 5. Simulate compensation workers:
    //    - comp-2 SUCCEEDS
    //    - comp-1 SUCCEEDS
    // 6. Wait for execution COMPENSATED
    // 7. Assertions:
    //    - execution.status == COMPENSATED
    //    - comp-2 finishedAt < comp-1 finishedAt (comp-2 ran first, i.e. in reverse)
    //    - stepExecution for step-2 has status DEAD_LETTERED (permanent failure)
    //    - stepExecution for step-0 has NO compensation (none defined)
    //    - compensationStepExecution for comp-1 and comp-2 both have status SUCCEEDED
}
```

**Key classes:** `WorkflowDefinitionService`, `WorkflowExecutionService`, `StepResultProcessor`, `CompensationEngine`, `StepExecutionRepository`

**Design spec reference:** Scenario 4 (full compensation walkthrough); Guarantee: "compensation only for completed steps, in reverse completion order"

---

### Task 4 — `DeduplicationTest.java`: Test 3 — Duplicate step result is deduplicated

**File:** `workflow-service/src/test/java/com/atlas/workflow/chaos/DeduplicationTest.java`

**What to implement:**

```java
@Test
void duplicateStepResultIsIgnored() {
    // 1. Register workflow definition with 2 steps
    // 2. Start execution
    // 3. Simulate worker: step-0 SUCCEEDS (call stepResultProcessor.process() once)
    // 4. Record execution state after first result: step-0 status, execution status,
    //    next step's state
    // 5. Call stepResultProcessor.process() again with the IDENTICAL StepResultEvent
    //    (same stepExecutionId, same attemptCount)
    // 6. Assertions:
    //    - step-0 status is still SUCCEEDED (unchanged)
    //    - execution has only advanced once (step-1 is pending, not twice-advanced)
    //    - no exception thrown on duplicate
    //    - deduplication counter metric incremented (if metrics are accessible)
    //    - audit log has exactly 1 "step succeeded" event for this stepExecutionId
}
```

**Key classes:** `StepResultProcessor`, `StepExecutionRepository`, `WorkflowExecutionRepository`

**Design spec reference:** Scenario 3 (Duplicate Message Delivery); Guarantee: "at most once per attempt"

---

### Task 5 — `IdempotencyTest.java`: Test 5 — Idempotent execution start

**File:** `workflow-service/src/test/java/com/atlas/workflow/chaos/IdempotencyTest.java`

**What to implement:**

```java
@Test
void startingExecutionTwiceWithSameIdempotencyKeyReturnsSameExecution() {
    // 1. Register workflow definition
    // 2. Build a StartExecutionRequest with an explicit idempotencyKey
    // 3. Call workflowExecutionService.start(request) — first call
    // 4. Record returned execution ID
    // 5. Call workflowExecutionService.start(request) again with same idempotencyKey
    //    This should NOT throw; it should return the existing execution
    // 6. Assertions:
    //    - Both calls return the same execution ID
    //    - Only 1 execution row exists in workflow_executions for this tenant + key
    //    - Second call returns HTTP 200 (not 201), or service returns same entity
    //      (follow the service's actual idempotency contract — 409 or silent return)
}
```

**Note on 409 vs silent return:** Check `WorkflowExecutionService.start()` to see whether a duplicate key throws an `AlreadyExistsException` (which the REST layer maps to 409) or returns the existing entity. The test should handle whichever behavior is implemented. If it throws, assert the exception type and that the DB still has exactly one row.

**Key classes:** `WorkflowExecutionService`, `WorkflowExecutionRepository`

**Design spec reference:** Guarantee: "idempotency key produces exactly one execution"; `UNIQUE` constraint on `(tenant_id, idempotency_key)`

---

### Task 6 — `CancellationTest.java`: Test 6 — Cancellation stops execution

**File:** `workflow-service/src/test/java/com/atlas/workflow/chaos/CancellationTest.java`

**What to implement:**

```java
@Test
void cancellingExecutionStopsRemainingSteps() {
    // 1. Register workflow definition with 3 steps
    // 2. Start execution
    // 3. Simulate worker: step-0 SUCCEEDS
    // 4. Before simulating step-1: cancel the execution
    //    Call workflowExecutionService.cancel(tenantId, executionId)
    // 5. Attempt to simulate step-1 SUCCEEDS (should be ignored or throw)
    // 6. Assertions:
    //    - execution.status == CANCELED
    //    - step-1 and step-2 are NOT in SUCCEEDED state
    //    - step-1 was never started (status PENDING or SKIPPED, not RUNNING/SUCCEEDED)
    //    - No further outbox rows published after cancellation
    //    - audit log contains "execution canceled" event
}
```

**Key classes:** `WorkflowExecutionService`, `WorkflowExecutionRepository`, `StepExecutionRepository`, `OutboxRepository` (to verify no new publish)

**Design spec reference:** Cancellation state machine transition; execution terminal state CANCELED

---

### Task 7 — `CompensationChaosTest.java`: Test 7 — Full order fulfillment saga with failure injection

**File:** Same class as Task 3 (`CompensationChaosTest.java`)

**What to implement:**

```java
@Test
void orderFulfillmentSagaCompensatesOnShipmentFailure() {
    // 1. Register the order-fulfillment workflow definition (from the demo):
    //    Steps: validate-order → reserve-inventory → charge-payment → create-shipment
    //    Compensations: release-inventory (reverses reserve-inventory),
    //                   refund-payment (reverses charge-payment)
    //    create-shipment has no compensation (terminal forward step)
    // 2. Start execution with failure_config targeting create-shipment: PERMANENT
    // 3. Simulate workers:
    //    - validate-order SUCCEEDS
    //    - reserve-inventory SUCCEEDS
    //    - charge-payment SUCCEEDS
    //    - create-shipment FAILS (permanent)
    // 4. Execution enters COMPENSATING
    // 5. Simulate compensation workers (reverse order):
    //    - refund-payment SUCCEEDS
    //    - release-inventory SUCCEEDS
    // 6. Wait for COMPENSATED
    // 7. Assertions:
    //    - execution.status == COMPENSATED
    //    - refund-payment finishedAt < release-inventory finishedAt
    //      (refund ran first — reverses charge-payment which ran later)
    //    - create-shipment stepExecution status == DEAD_LETTERED
    //    - validate-order has no compensation (none defined)
    //    - All 4 forward steps and 2 compensation steps appear in timeline
    //    - Audit log contains full forward + compensation narrative
}
```

**Key classes:** `WorkflowDefinitionService`, `WorkflowExecutionService`, `StepResultProcessor`, `CompensationEngine`, `StepExecutionRepository`, `TimelineService` (if available)

**Design spec reference:** Scenario 4 (exact timeline from design spec); full saga pattern end-to-end

---

## Implementation Notes

### Reading failure injection from step input

The `InternalCommandExecutor` must parse `failure_config` from the step's input map. When writing test workflow definitions, embed the failure config in the step's static input or allow the execution's input to merge into step input. Confirm the exact merge strategy in `WorkflowExecutionService` / step input resolution before writing tests.

### Compensation step result simulation

When `CompensationEngine` publishes compensation step commands, the outbox will contain new step-execute entries with a `compensationFor` field. The test must:
1. After each compensation command is published, retrieve the new compensation `stepExecutionId` from the repository.
2. Call `simulateWorkerSuccess(compensationStepExecutionId, 1)` to simulate it completing.

### State assertion helpers

Extract a reusable assertion helper to keep tests readable:

```java
void assertStepStatus(UUID execId, String stepKey, StepStatus expected) { ... }
void assertCompensationOrder(UUID execId, String firstComp, String secondComp) { ... }
void assertDeadLetterExists(UUID stepExecutionId) { ... }
```

### Test isolation

Each test must create its own `tenantId` (random UUID) and workflow definition name to avoid cross-test interference. Use `@Transactional` only if the test is fully synchronous; for async tests that rely on scheduler-driven state changes, do NOT use `@Transactional` on the test method — let each service call commit independently.

### Awaitility timeouts

Use conservative timeouts (10s max per await) since Testcontainers startup is slow but step processing should be near-instant in direct-invocation mode. The only legitimate delay is scheduler-driven retry: if the retry scheduler runs on a fixed 1s interval, a 5s timeout is sufficient.

---

## Acceptance Criteria

All 7 tests pass with:
- No flakiness on 3 consecutive runs
- Each test completes in under 15 seconds (excluding container startup)
- Tests are independent: running any single test in isolation produces the same result
- No shared mutable state between test classes
- `mvn test -pl workflow-service -Dtest="chaos/*Test"` runs the full suite green
