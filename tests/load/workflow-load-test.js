import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 10 },   // ramp up to 10 VUs
    { duration: '1m',  target: 50 },   // ramp up to 50 VUs
    { duration: '30s', target: 0 },    // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],  // 95th percentile under 500ms
    http_req_failed:   ['rate<0.01'],  // less than 1% errors
  },
};

const IDENTITY_BASE = __ENV.IDENTITY_URL || 'http://localhost:8081';
const WORKFLOW_BASE = __ENV.WORKFLOW_URL || 'http://localhost:8082';

// Fixed seed definition ID created by `make seed`
const SEED_DEFINITION_ID = 'c0000000-0000-0000-0000-000000000001';

// ---- Setup: runs once before VUs start ----------------------------
export function setup() {
  // Login as admin to get a token
  const loginRes = http.post(
    `${IDENTITY_BASE}/api/v1/auth/login`,
    JSON.stringify({ email: 'admin@acme.com', password: 'Atlas2026!' }),
    { headers: { 'Content-Type': 'application/json' } },
  );

  check(loginRes, {
    'setup: login succeeded': (r) => r.status === 200,
  });

  const token = loginRes.json('accessToken');
  if (!token) {
    throw new Error('setup: could not obtain accessToken — is identity-service running?');
  }

  // Verify the seed definition is published; fall back to creating one
  const defRes = http.get(
    `${WORKFLOW_BASE}/api/v1/workflow-definitions/${SEED_DEFINITION_ID}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );

  let definitionId = SEED_DEFINITION_ID;

  if (defRes.status !== 200) {
    // Seed definition not found — create and publish a minimal one for the load test
    const createRes = http.post(
      `${WORKFLOW_BASE}/api/v1/workflow-definitions`,
      JSON.stringify({
        name: 'load-test-noop',
        version: 1,
        steps: [
          {
            step_id: 'noop',
            name: 'No-op',
            type: 'INTERNAL_COMMAND',
            executor: 'noop-executor',
          },
        ],
      }),
      { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } },
    );

    check(createRes, {
      'setup: definition created': (r) => r.status === 201,
    });

    definitionId = createRes.json('definitionId');

    const pubRes = http.post(
      `${WORKFLOW_BASE}/api/v1/workflow-definitions/${definitionId}/publish`,
      null,
      { headers: { Authorization: `Bearer ${token}` } },
    );

    check(pubRes, {
      'setup: definition published': (r) => r.status === 200,
    });
  }

  return { token, definitionId };
}

// ---- Default function: runs per VU per iteration -----------------
export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${data.token}`,
  };

  // Unique idempotency key per virtual user + iteration + timestamp
  const idempotencyKey = `load-${__VU}-${__ITER}-${Date.now()}`;

  const startRes = http.post(
    `${WORKFLOW_BASE}/api/v1/workflow-executions`,
    JSON.stringify({
      definitionId: data.definitionId,
      idempotencyKey,
      input: {
        order_id: `ORD-LOAD-${__VU}-${__ITER}`,
        customer_id: 'CUST-LOAD-001',
        items: [{ sku: 'SKU-LOAD', qty: 1 }],
        loadTest: true,
      },
    }),
    { headers },
  );

  check(startRes, {
    'execution started (201)': (r) => r.status === 201,
    'response has executionId': (r) => r.json('executionId') !== undefined,
  });

  // Brief pause between iterations — avoids thundering-herd on a single connection
  sleep(0.5);
}

// ---- Teardown: runs once after all VUs finish --------------------
export function teardown(data) {
  // Nothing to clean up — executions are left for inspection
}
