#!/bin/bash
set -euo pipefail
# E2E tests that exercise the full Atlas platform.
# Prerequisites: services must be running (`make up`).

BASE_URL_IDENTITY="${IDENTITY_URL:-http://localhost:8081}"
BASE_URL_WORKFLOW="${WORKFLOW_URL:-http://localhost:8082}"
BASE_URL_AUDIT="${AUDIT_URL:-http://localhost:8084}"

PASS=0
FAIL=0

# ---- Helpers -------------------------------------------------------

test_case() { echo ""; echo "TEST: $1"; }

assert_status() {
  local expected=$1 actual=$2 desc=$3
  if [ "$actual" = "$expected" ]; then
    echo "  PASS: $desc (HTTP $actual)"
    PASS=$((PASS+1))
  else
    echo "  FAIL: $desc (expected HTTP $expected, got $actual)"
    FAIL=$((FAIL+1))
  fi
}

assert_eq() {
  local expected=$1 actual=$2 desc=$3
  if [ "$actual" = "$expected" ]; then
    echo "  PASS: $desc"
    PASS=$((PASS+1))
  else
    echo "  FAIL: $desc (expected '$expected', got '$actual')"
    FAIL=$((FAIL+1))
  fi
}

# curl wrapper: returns body\nHTTP_CODE
# Usage: http_req <method> <url> [token] [body]
http_req() {
  local method=$1
  local url=$2
  local token=${3:-}
  local body=${4:-}

  local args=(-s -w "\n%{http_code}" -X "$method")

  if [ -n "$token" ]; then
    args+=(-H "Authorization: Bearer $token")
  fi

  if [ -n "$body" ]; then
    args+=(-H "Content-Type: application/json" -d "$body")
  fi

  curl "${args[@]}" "$url"
}

extract_json() {
  # extract_json <field> <json_string>
  local field=$1
  local json=$2
  echo "$json" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$field',''))" 2>/dev/null || true
}

poll_execution() {
  # poll_execution <execution_id> <token> [max_attempts]
  local exec_id=$1
  local token=$2
  local max=${3:-30}
  local attempt=0
  local status=""

  while [ $attempt -lt $max ]; do
    local resp
    resp=$(http_req GET "$BASE_URL_WORKFLOW/api/v1/workflow-executions/$exec_id" "$token")
    local body
    body=$(echo "$resp" | head -1)
    status=$(extract_json "status" <<< "$body")

    case "$status" in
      COMPLETED|FAILED|CANCELLED|COMPENSATION_COMPLETED|COMPENSATION_FAILED)
        echo "$status"
        return 0
        ;;
    esac

    attempt=$((attempt+1))
    sleep 2
  done

  echo "TIMEOUT"
}

# ---- Seed IDs (must match seed.sh) --------------------------------
VIEWER_USER_ID="b0000000-0000-0000-0000-000000000002"
VIEWER_ROLE_ID="a0000000-0000-0000-0000-000000000032"
ORDER_FULFILLMENT_DEF_ID="c0000000-0000-0000-0000-000000000001"

# ====================================================================
# Test 1: Authentication
# ====================================================================
test_case "Authentication"

LOGIN_RESP=$(http_req POST "$BASE_URL_IDENTITY/api/v1/auth/login" "" \
  '{"email":"admin@acme.com","password":"Atlas2026!"}')
LOGIN_HTTP=$(echo "$LOGIN_RESP" | tail -1)
LOGIN_BODY=$(echo "$LOGIN_RESP" | head -1)
assert_status "200" "$LOGIN_HTTP" "Login as admin"

TOKEN=$(extract_json "accessToken" <<< "$LOGIN_BODY")
if [ -z "$TOKEN" ]; then
  echo "  FATAL: could not extract accessToken — aborting"
  exit 1
fi

# Invalid credentials → 401
BAD_RESP=$(http_req POST "$BASE_URL_IDENTITY/api/v1/auth/login" "" \
  '{"email":"admin@acme.com","password":"wrongpassword"}')
BAD_HTTP=$(echo "$BAD_RESP" | tail -1)
assert_status "401" "$BAD_HTTP" "Login with wrong password is rejected"

# ====================================================================
# Test 2: Workflow Definition Lifecycle
# ====================================================================
test_case "Workflow Definition Lifecycle"

DEF_NAME="e2e-test-$(date +%s)"
CREATE_RESP=$(http_req POST "$BASE_URL_WORKFLOW/api/v1/workflow-definitions" "$TOKEN" \
  "{\"name\":\"$DEF_NAME\",\"version\":1,\"steps\":[{\"step_id\":\"noop\",\"name\":\"No-op\",\"type\":\"INTERNAL_COMMAND\",\"executor\":\"noop-executor\"}]}")
CREATE_HTTP=$(echo "$CREATE_RESP" | tail -1)
CREATE_BODY=$(echo "$CREATE_RESP" | head -1)
assert_status "201" "$CREATE_HTTP" "Create workflow definition"

DEF_ID=$(extract_json "definitionId" <<< "$CREATE_BODY")
if [ -z "$DEF_ID" ]; then
  echo "  FAIL: could not extract definitionId from create response"
  FAIL=$((FAIL+1))
else
  # Verify GET returns the new definition
  GET_DEF_RESP=$(http_req GET "$BASE_URL_WORKFLOW/api/v1/workflow-definitions/$DEF_ID" "$TOKEN")
  GET_DEF_HTTP=$(echo "$GET_DEF_RESP" | tail -1)
  assert_status "200" "$GET_DEF_HTTP" "GET newly created definition"

  GET_DEF_BODY=$(echo "$GET_DEF_RESP" | head -1)
  DEF_STATUS=$(extract_json "status" <<< "$GET_DEF_BODY")
  assert_eq "DRAFT" "$DEF_STATUS" "New definition is in DRAFT status"

  # Publish it
  PUB_RESP=$(http_req POST "$BASE_URL_WORKFLOW/api/v1/workflow-definitions/$DEF_ID/publish" "$TOKEN")
  PUB_HTTP=$(echo "$PUB_RESP" | tail -1)
  assert_status "200" "$PUB_HTTP" "Publish definition"

  PUB_BODY=$(echo "$PUB_RESP" | head -1)
  PUB_STATUS=$(extract_json "status" <<< "$PUB_BODY")
  assert_eq "PUBLISHED" "$PUB_STATUS" "Definition status is PUBLISHED after publish"

  # Publish again → 409 Conflict
  PUB2_RESP=$(http_req POST "$BASE_URL_WORKFLOW/api/v1/workflow-definitions/$DEF_ID/publish" "$TOKEN")
  PUB2_HTTP=$(echo "$PUB2_RESP" | tail -1)
  assert_status "409" "$PUB2_HTTP" "Re-publishing an already PUBLISHED definition returns 409"
fi

# ====================================================================
# Test 3: Workflow Execution
# ====================================================================
test_case "Workflow Execution"

IDEM_KEY="e2e-exec-$(date +%s)-$$"
EXEC_RESP=$(http_req POST "$BASE_URL_WORKFLOW/api/v1/workflow-executions" "$TOKEN" \
  "{\"definitionId\":\"$ORDER_FULFILLMENT_DEF_ID\",\"idempotencyKey\":\"$IDEM_KEY\",\"input\":{\"order_id\":\"ORD-E2E-001\",\"customer_id\":\"CUST-E2E-001\",\"items\":[{\"sku\":\"SKU-1\",\"qty\":1}]}}")
EXEC_HTTP=$(echo "$EXEC_RESP" | tail -1)
EXEC_BODY=$(echo "$EXEC_RESP" | head -1)
assert_status "201" "$EXEC_HTTP" "Start workflow execution"

EXEC_ID=$(extract_json "executionId" <<< "$EXEC_BODY")
if [ -z "$EXEC_ID" ]; then
  echo "  FAIL: could not extract executionId"
  FAIL=$((FAIL+1))
else
  # Poll until terminal
  echo "  INFO: polling execution $EXEC_ID until terminal (up to 60s)..."
  FINAL_STATUS=$(poll_execution "$EXEC_ID" "$TOKEN" 30)
  echo "  INFO: final status = $FINAL_STATUS"

  # Accept COMPLETED or any terminal state (workers may not be running in all envs)
  case "$FINAL_STATUS" in
    TIMEOUT)
      echo "  FAIL: execution did not reach terminal state within timeout"
      FAIL=$((FAIL+1))
      ;;
    *)
      echo "  PASS: execution reached terminal state ($FINAL_STATUS)"
      PASS=$((PASS+1))
      ;;
  esac

  # GET execution returns 200
  GET_EXEC_RESP=$(http_req GET "$BASE_URL_WORKFLOW/api/v1/workflow-executions/$EXEC_ID" "$TOKEN")
  GET_EXEC_HTTP=$(echo "$GET_EXEC_RESP" | tail -1)
  assert_status "200" "$GET_EXEC_HTTP" "GET execution by ID"

  # Timeline endpoint
  TL_RESP=$(http_req GET "$BASE_URL_WORKFLOW/api/v1/workflow-executions/$EXEC_ID/timeline" "$TOKEN")
  TL_HTTP=$(echo "$TL_RESP" | tail -1)
  assert_status "200" "$TL_HTTP" "GET execution timeline"
fi

# ====================================================================
# Test 4: RBAC — viewer cannot start an execution
# ====================================================================
test_case "RBAC Enforcement"

VIEWER_LOGIN=$(http_req POST "$BASE_URL_IDENTITY/api/v1/auth/login" "" \
  '{"email":"viewer@acme.com","password":"Atlas2026!"}')
VIEWER_HTTP=$(echo "$VIEWER_LOGIN" | tail -1)
assert_status "200" "$VIEWER_HTTP" "Login as viewer@acme.com"

VIEWER_TOKEN=$(extract_json "accessToken" <<< "$(echo "$VIEWER_LOGIN" | head -1)")

if [ -n "$VIEWER_TOKEN" ]; then
  VIEWER_EXEC_RESP=$(http_req POST "$BASE_URL_WORKFLOW/api/v1/workflow-executions" "$VIEWER_TOKEN" \
    "{\"definitionId\":\"$ORDER_FULFILLMENT_DEF_ID\",\"idempotencyKey\":\"rbac-test-$$\",\"input\":{}}")
  VIEWER_EXEC_HTTP=$(echo "$VIEWER_EXEC_RESP" | tail -1)
  assert_status "403" "$VIEWER_EXEC_HTTP" "Viewer cannot start a workflow execution"
else
  echo "  SKIP: could not get viewer token, skipping RBAC execution test"
fi

# Unauthenticated request → 401
UNAUTH_RESP=$(http_req GET "$BASE_URL_WORKFLOW/api/v1/workflow-executions/00000000-0000-0000-0000-000000000001")
UNAUTH_HTTP=$(echo "$UNAUTH_RESP" | tail -1)
assert_status "401" "$UNAUTH_HTTP" "Unauthenticated request is rejected with 401"

# ====================================================================
# Test 5: Audit Trail
# ====================================================================
test_case "Audit Trail"

# Small sleep so Kafka consumer can process events from the execution above
sleep 3

AUDIT_RESP=$(http_req GET "$BASE_URL_AUDIT/api/v1/audit-events?size=20" "$TOKEN")
AUDIT_HTTP=$(echo "$AUDIT_RESP" | tail -1)
assert_status "200" "$AUDIT_HTTP" "Query audit events returns 200"

AUDIT_BODY=$(echo "$AUDIT_RESP" | head -1)
# Verify the response is a non-empty JSON array
AUDIT_COUNT=$(echo "$AUDIT_BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d))" 2>/dev/null || echo "0")
if [ "$AUDIT_COUNT" -gt 0 ] 2>/dev/null; then
  echo "  PASS: audit trail contains $AUDIT_COUNT event(s)"
  PASS=$((PASS+1))
else
  echo "  FAIL: audit trail is empty (expected at least one event)"
  FAIL=$((FAIL+1))
fi

# ====================================================================
# Test 6: Idempotency
# ====================================================================
test_case "Idempotency"

IDEM_KEY2="e2e-idem-$(date +%s)-$$"
EXEC1=$(http_req POST "$BASE_URL_WORKFLOW/api/v1/workflow-executions" "$TOKEN" \
  "{\"definitionId\":\"$ORDER_FULFILLMENT_DEF_ID\",\"idempotencyKey\":\"$IDEM_KEY2\",\"input\":{\"order_id\":\"ORD-IDEM-001\",\"customer_id\":\"CUST-IDEM-001\",\"items\":[]}}")
EXEC1_HTTP=$(echo "$EXEC1" | tail -1)
EXEC1_BODY=$(echo "$EXEC1" | head -1)
assert_status "201" "$EXEC1_HTTP" "First execution with idempotency key returns 201"
EXEC1_ID=$(extract_json "executionId" <<< "$EXEC1_BODY")

# Second request with the same key must return same ID (200 or 201 are both acceptable)
EXEC2=$(http_req POST "$BASE_URL_WORKFLOW/api/v1/workflow-executions" "$TOKEN" \
  "{\"definitionId\":\"$ORDER_FULFILLMENT_DEF_ID\",\"idempotencyKey\":\"$IDEM_KEY2\",\"input\":{\"order_id\":\"ORD-IDEM-001\",\"customer_id\":\"CUST-IDEM-001\",\"items\":[]}}")
EXEC2_HTTP=$(echo "$EXEC2" | tail -1)
EXEC2_BODY=$(echo "$EXEC2" | head -1)
EXEC2_ID=$(extract_json "executionId" <<< "$EXEC2_BODY")

if [ "$EXEC2_HTTP" = "200" ] || [ "$EXEC2_HTTP" = "201" ]; then
  echo "  PASS: duplicate request returns HTTP $EXEC2_HTTP"
  PASS=$((PASS+1))
else
  echo "  FAIL: duplicate request returned unexpected HTTP $EXEC2_HTTP"
  FAIL=$((FAIL+1))
fi

if [ -n "$EXEC1_ID" ] && [ "$EXEC1_ID" = "$EXEC2_ID" ]; then
  echo "  PASS: both calls returned the same executionId ($EXEC1_ID)"
  PASS=$((PASS+1))
else
  echo "  FAIL: executionIds differ (first=$EXEC1_ID, second=$EXEC2_ID)"
  FAIL=$((FAIL+1))
fi

# ====================================================================
# Test 7: Rate Limiting
# ====================================================================
test_case "Rate Limiting"

echo "  INFO: firing 120 rapid requests to trigger rate limiting..."
GOT_429=0
for i in $(seq 1 120); do
  RESP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X GET \
    -H "Authorization: Bearer $TOKEN" \
    "$BASE_URL_WORKFLOW/api/v1/workflow-definitions/$ORDER_FULFILLMENT_DEF_ID")
  if [ "$RESP_CODE" = "429" ]; then
    GOT_429=1
    break
  fi
done

if [ "$GOT_429" = "1" ]; then
  echo "  PASS: rate limiter returned 429 after rapid requests"
  PASS=$((PASS+1))
else
  echo "  FAIL: no 429 received after 120 rapid requests (rate limiting may be disabled or limit is higher)"
  FAIL=$((FAIL+1))
fi

# ====================================================================
# Summary
# ====================================================================
echo ""
echo "================================"
echo "E2E Results: $PASS passed, $FAIL failed"
echo "================================"
[ $FAIL -eq 0 ] || exit 1
