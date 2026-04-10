# Load Testing

## Prerequisites

- Install k6: `brew install k6`
- Start Atlas: `make up`

## Run

```bash
k6 run tests/load/workflow-load-test.js
```

## Options

Override service URLs via environment variables:

```bash
IDENTITY_URL=http://localhost:8081 WORKFLOW_URL=http://localhost:8082 \
  k6 run tests/load/workflow-load-test.js
```

Run with a custom VU / duration profile:

```bash
k6 run --vus 20 --duration 2m tests/load/workflow-load-test.js
```

## What the test does

1. **Setup** — logs in as `admin@acme.com` and verifies (or creates) a published workflow definition.
2. **Load phase** — ramps from 10 → 50 virtual users over 2 minutes, each firing `POST /api/v1/workflow-executions` with a unique idempotency key.
3. **Ramp-down** — smoothly reduces VUs back to 0.

## Thresholds

| Metric | Threshold |
|---|---|
| `http_req_duration` | p(95) < 500 ms |
| `http_req_failed` | rate < 1 % |

The test exits with a non-zero code if either threshold is violated.
