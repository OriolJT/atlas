# Atlas Demo Guide

This guide walks through a complete end-to-end demonstration of Atlas. It covers the two built-in demo workflows, failure injection, RBAC verification, and observability.

**Prerequisites:** Docker and Docker Compose installed. See [local-setup.md](local-setup.md) if you need to build first.

---

## 1. Start the Stack

```bash
make up
```

This brings up all services and infrastructure. Wait for all health checks to pass (typically 30–60 seconds):

```bash
make health
```

Expected output:
```
identity-service:  UP
workflow-service:  UP
worker-service:    UP
audit-service:     UP
```

---

## 2. Run the Seed Script

The seed script authenticates as the bootstrap admin, creates demo users, and registers both workflow definitions. It is idempotent.

```bash
make seed
```

This creates:
- `admin@acme.com` / `Atlas2026!` — `TENANT_ADMIN`
- `operator@acme.com` / `Atlas2026!` — `WORKFLOW_OPERATOR`
- `viewer@acme.com` / `Atlas2026!` — `VIEWER`
- Workflow definition: `order-fulfillment` (published)
- Workflow definition: `incident-escalation` (published)

---

## 3. Authenticate as Admin

```bash
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@acme.com","password":"Atlas2026!"}' \
  | jq -r '.access_token')

echo $TOKEN
```

Store the token for subsequent requests. Tokens expire after 15 minutes; re-run this command to get a fresh one.

---

## 4. Trigger the Order Fulfillment Workflow

### 4a. Get the definition ID

```bash
curl -s http://localhost:8082/api/v1/workflow-definitions \
  -H "Authorization: Bearer $TOKEN" \
  | jq '.[] | select(.name == "order-fulfillment") | .definition_id'
```

Store the result:
```bash
DEFINITION_ID=<value from above>
```

### 4b. Start an execution (happy path)

```bash
EXEC_ID=$(curl -s -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-run-001" \
  -d "{
    \"definition_id\": \"$DEFINITION_ID\",
    \"input\": {
      \"order_id\": \"order-001\"
    }
  }" | jq -r '.execution_id')

echo $EXEC_ID
```

---

## 5. Watch Execution Progress

Poll the execution status to watch it advance through steps:

```bash
watch -n 1 "curl -s http://localhost:8082/api/v1/workflow-executions/$EXEC_ID \
  -H 'Authorization: Bearer $TOKEN' | jq '{status, current_step, steps: [.steps[] | {step_name, status, attempt_count}]}'"
```

You will see the execution move through `PENDING → RUNNING` and each step advance through `PENDING → LEASED → RUNNING → SUCCEEDED`. The full execution completes in a few seconds.

When complete, fetch the timeline:

```bash
curl -s http://localhost:8082/api/v1/workflow-executions/$EXEC_ID/timeline \
  -H "Authorization: Bearer $TOKEN" | jq '.'
```

---

## 6. Trigger Failure to See Retries and Compensation

Start a new execution with failure injection. This causes `create-shipment` to fail permanently after 1 attempt, triggering compensation.

```bash
FAIL_EXEC_ID=$(curl -s -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-fail-001" \
  -d "{
    \"definition_id\": \"$DEFINITION_ID\",
    \"input\": {
      \"order_id\": \"order-002\",
      \"failure_config\": {
        \"fail_at_step\": \"create-shipment\",
        \"failure_type\": \"PERMANENT\"
      }
    }
  }" | jq -r '.execution_id')

echo $FAIL_EXEC_ID
```

Watch the execution:

```bash
watch -n 1 "curl -s http://localhost:8082/api/v1/workflow-executions/$FAIL_EXEC_ID \
  -H 'Authorization: Bearer $TOKEN' | jq '{status, steps: [.steps[] | {step_name, status}]}'"
```

Expected progression:
1. `create-shipment` transitions to `FAILED`
2. Execution transitions to `COMPENSATING`
3. `refund-payment` and `release-inventory` run in reverse order
4. Execution reaches `COMPENSATED`

To see retries before eventual failure, use `failure_type: "TRANSIENT"` with `fail_after_attempts: 2`:

```bash
RETRY_EXEC_ID=$(curl -s -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-retry-001" \
  -d "{
    \"definition_id\": \"$DEFINITION_ID\",
    \"input\": {
      \"order_id\": \"order-003\",
      \"failure_config\": {
        \"fail_at_step\": \"charge-payment\",
        \"failure_type\": \"TRANSIENT\",
        \"fail_after_attempts\": 2
      }
    }
  }" | jq -r '.execution_id')
```

Watch `charge-payment` fail twice and succeed on attempt 3.

---

## 7. Trigger the Incident Escalation Workflow (EVENT_WAIT)

```bash
INCIDENT_DEF=$(curl -s http://localhost:8082/api/v1/workflow-definitions \
  -H "Authorization: Bearer $TOKEN" \
  | jq -r '.[] | select(.name == "incident-escalation") | .definition_id')

INCIDENT_EXEC=$(curl -s -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-incident-001" \
  -d "{\"definition_id\": \"$INCIDENT_DEF\", \"input\": {\"incident_id\": \"inc-001\"}}" \
  | jq -r '.execution_id')
```

The execution will pause at `wait-for-acknowledgment` (status `WAITING`).

**Option A — Send acknowledgment signal (execution advances normally):**

```bash
curl -s -X POST http://localhost:8082/api/v1/workflow-executions/$INCIDENT_EXEC/signal \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"step_name": "wait-for-acknowledgment", "payload": {"acknowledged_by": "ops-user-1"}}'
```

**Option B — Wait 60 seconds for the timeout to fire.** The execution will automatically advance to `escalate-if-timeout`.

---

## 8. Verify RBAC — Login as Viewer

```bash
VIEWER_TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"viewer@acme.com","password":"Atlas2026!"}' \
  | jq -r '.access_token')
```

Viewer can read executions:

```bash
curl -s http://localhost:8082/api/v1/workflow-executions/$EXEC_ID \
  -H "Authorization: Bearer $VIEWER_TOKEN" | jq '.status'
# returns the execution status
```

Viewer cannot start a new execution:

```bash
curl -s -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Authorization: Bearer $VIEWER_TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: viewer-attempt-001" \
  -d "{\"definition_id\": \"$DEFINITION_ID\", \"input\": {}}" | jq '.code'
# returns "ATLAS-AUTH-004"
```

Viewer cannot cancel an execution:

```bash
curl -s -X POST http://localhost:8082/api/v1/workflow-executions/$EXEC_ID/cancel \
  -H "Authorization: Bearer $VIEWER_TOKEN" | jq '.code'
# returns "ATLAS-AUTH-004"
```

---

## 9. Inspect the Audit Trail

```bash
curl -s "http://localhost:8084/api/v1/audit-events?size=20" \
  -H "Authorization: Bearer $TOKEN" \
  | jq '[.events[] | {event_type, actor_type, resource_type, occurred_at}]'
```

Filter by execution:

```bash
curl -s "http://localhost:8084/api/v1/audit-events?resource_id=$EXEC_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '.'
```

Filter by event type:

```bash
curl -s "http://localhost:8084/api/v1/audit-events?event_type=workflow.execution.compensating" \
  -H "Authorization: Bearer $TOKEN" | jq '.'
```

---

## 10. Open Grafana Dashboards

Open [http://localhost:3000](http://localhost:3000) in a browser (default credentials: `admin` / `admin`).

Four dashboards are pre-provisioned:

| Dashboard | What to look for |
|-----------|------------------|
| **Platform Health** | Service up/down status, JVM heap, HTTP error rates, Kafka consumer lag, DB connection pool |
| **Workflow Executions** | Execution start/complete/fail rates, step duration histogram, compensation rate |
| **Failures and Retries** | Retry rate by step, dead-letter queue depth, timeout frequency |
| **Tenant Activity** | Per-tenant execution volume, API request rate, auth failure rate |

After running the failure injection demos, the Failures and Retries dashboard will show step retry activity. The compensation rate metric on the Workflow Executions dashboard reflects the `COMPENSATING` transitions.

---

## 11. Inspect Dead-Letter Items

If any step exhausted all retries without succeeding, it will appear in the dead-letter queue:

```bash
curl -s http://localhost:8082/api/v1/dead-letter \
  -H "Authorization: Bearer $TOKEN" | jq '.'
```

To replay a dead-letter item (after fixing the underlying cause):

```bash
DL_ID=<dead_letter_id from above>

curl -s -X POST http://localhost:8082/api/v1/dead-letter/$DL_ID/replay \
  -H "Authorization: Bearer $TOKEN" | jq '.'
```

---

## Quick Reference

| Action | Command |
|--------|---------|
| Get admin token | `curl -s -X POST localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@acme.com","password":"Atlas2026!"}'` |
| List executions | `curl -s localhost:8082/api/v1/workflow-executions -H "Authorization: Bearer $TOKEN"` |
| Tail service logs | `docker compose logs -f workflow-service` |
| Stop the stack | `make down` |
| Reset all data | `make reset` |
