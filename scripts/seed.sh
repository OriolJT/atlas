#!/usr/bin/env bash
# ============================================================
# Atlas Seed Script
# Idempotent: safe to run multiple times.
# Uses fixed UUIDs and idempotency keys for reproducibility.
# ============================================================

set -euo pipefail

IDENTITY_URL="${IDENTITY_URL:-http://localhost:8081}"
WORKFLOW_URL="${WORKFLOW_URL:-http://localhost:8082}"
EXAMPLES_DIR="${EXAMPLES_DIR:-$(dirname "$0")/../examples/workflows}"

# ---- Fixed UUIDs (idempotent) --------------------------------
OPERATOR_USER_ID="b0000000-0000-0000-0000-000000000001"
VIEWER_USER_ID="b0000000-0000-0000-0000-000000000002"
WORKFLOW_OPERATOR_ROLE_ID="a0000000-0000-0000-0000-000000000031"
VIEWER_ROLE_ID="a0000000-0000-0000-0000-000000000032"
ORDER_FULFILLMENT_DEF_ID="c0000000-0000-0000-0000-000000000001"
INCIDENT_ESCALATION_DEF_ID="c0000000-0000-0000-0000-000000000002"

# ---- Colour helpers ------------------------------------------
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()    { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# ---- Wait for service health ---------------------------------
wait_healthy() {
  local name="$1"
  local url="$2"
  local max_attempts=60
  local attempt=0

  info "Waiting for $name to be healthy at $url/actuator/health ..."
  until curl -sf "$url/actuator/health" | grep -q '"status":"UP"'; do
    attempt=$((attempt + 1))
    if [[ $attempt -ge $max_attempts ]]; then
      error "$name did not become healthy within $((max_attempts * 2))s. Aborting."
      exit 1
    fi
    echo -n "."
    sleep 2
  done
  echo ""
  info "$name is healthy."
}

# ---- HTTP helpers --------------------------------------------
post_json() {
  local url="$1"
  local token="$2"
  local body="$3"
  curl -sf \
    -X POST \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $token" \
    -d "$body" \
    "$url"
}

post_json_no_auth() {
  local url="$1"
  local body="$2"
  curl -sf \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$body" \
    "$url"
}

post_empty() {
  local url="$1"
  local token="$2"
  curl -sf \
    -X POST \
    -H "Authorization: Bearer $token" \
    "$url"
}

# ---- 1. Wait for services ------------------------------------
info "============================================================"
info "Atlas Seed Script — starting"
info "============================================================"

wait_healthy "identity-service" "$IDENTITY_URL"
wait_healthy "workflow-service" "$WORKFLOW_URL"

# ---- 2. Login as admin@acme.com -----------------------------
info "Authenticating as admin@acme.com ..."

LOGIN_RESPONSE=$(post_json_no_auth \
  "$IDENTITY_URL/api/v1/auth/login" \
  '{"email":"admin@acme.com","password":"Atlas2026!"}')

ADMIN_TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

if [[ -z "$ADMIN_TOKEN" ]]; then
  error "Failed to obtain admin JWT. Check identity-service logs."
  exit 1
fi
info "Admin JWT obtained."

# ---- 3. Create operator@acme.com ----------------------------
info "Creating operator@acme.com ..."
post_json \
  "$IDENTITY_URL/api/v1/users" \
  "$ADMIN_TOKEN" \
  "{
    \"user_id\": \"$OPERATOR_USER_ID\",
    \"email\": \"operator@acme.com\",
    \"password\": \"Atlas2026!\",
    \"first_name\": \"Workflow\",
    \"last_name\": \"Operator\"
  }" > /dev/null || warn "operator@acme.com may already exist — continuing."

# ---- 4. Create viewer@acme.com ------------------------------
info "Creating viewer@acme.com ..."
post_json \
  "$IDENTITY_URL/api/v1/users" \
  "$ADMIN_TOKEN" \
  "{
    \"user_id\": \"$VIEWER_USER_ID\",
    \"email\": \"viewer@acme.com\",
    \"password\": \"Atlas2026!\",
    \"first_name\": \"Read\",
    \"last_name\": \"Only\"
  }" > /dev/null || warn "viewer@acme.com may already exist — continuing."

# ---- 5. Assign WORKFLOW_OPERATOR role to operator -----------
info "Assigning WORKFLOW_OPERATOR role to operator@acme.com ..."
post_json \
  "$IDENTITY_URL/api/v1/users/$OPERATOR_USER_ID/roles" \
  "$ADMIN_TOKEN" \
  "{\"role_id\": \"$WORKFLOW_OPERATOR_ROLE_ID\"}" > /dev/null \
  || warn "Role assignment may already exist — continuing."

# ---- 6. Assign VIEWER role to viewer@acme.com ---------------
info "Assigning VIEWER role to viewer@acme.com ..."
post_json \
  "$IDENTITY_URL/api/v1/users/$VIEWER_USER_ID/roles" \
  "$ADMIN_TOKEN" \
  "{\"role_id\": \"$VIEWER_ROLE_ID\"}" > /dev/null \
  || warn "Role assignment may already exist — continuing."

# ---- 7. Register order-fulfillment workflow -----------------
info "Registering order-fulfillment workflow definition ..."

ORDER_DEF_PAYLOAD=$(jq -c \
  --arg id "$ORDER_FULFILLMENT_DEF_ID" \
  '. + {"definition_id": $id}' \
  "$EXAMPLES_DIR/order-fulfillment.json")

post_json \
  "$WORKFLOW_URL/api/v1/workflow-definitions" \
  "$ADMIN_TOKEN" \
  "$ORDER_DEF_PAYLOAD" > /dev/null \
  || warn "order-fulfillment definition may already exist — continuing."

# ---- 8. Publish order-fulfillment workflow ------------------
info "Publishing order-fulfillment workflow definition ..."
post_empty \
  "$WORKFLOW_URL/api/v1/workflow-definitions/$ORDER_FULFILLMENT_DEF_ID/publish" \
  "$ADMIN_TOKEN" > /dev/null \
  || warn "order-fulfillment may already be published — continuing."

# ---- 9. Register incident-escalation workflow ---------------
info "Registering incident-escalation workflow definition ..."

INCIDENT_DEF_PAYLOAD=$(jq -c \
  --arg id "$INCIDENT_ESCALATION_DEF_ID" \
  '. + {"definition_id": $id}' \
  "$EXAMPLES_DIR/incident-escalation.json")

post_json \
  "$WORKFLOW_URL/api/v1/workflow-definitions" \
  "$ADMIN_TOKEN" \
  "$INCIDENT_DEF_PAYLOAD" > /dev/null \
  || warn "incident-escalation definition may already exist — continuing."

# ---- 10. Publish incident-escalation workflow ---------------
info "Publishing incident-escalation workflow definition ..."
post_empty \
  "$WORKFLOW_URL/api/v1/workflow-definitions/$INCIDENT_ESCALATION_DEF_ID/publish" \
  "$ADMIN_TOKEN" > /dev/null \
  || warn "incident-escalation may already be published — continuing."

# ---- 11. Summary --------------------------------------------
info "============================================================"
info "Seed complete. Atlas is ready to demo."
info "------------------------------------------------------------"
info "Users:"
info "  admin@acme.com    / Atlas2026!  (TENANT_ADMIN)"
info "  operator@acme.com / Atlas2026!  (WORKFLOW_OPERATOR)"
info "  viewer@acme.com   / Atlas2026!  (VIEWER)"
info ""
info "Workflow Definitions:"
info "  order-fulfillment    ID: $ORDER_FULFILLMENT_DEF_ID  (PUBLISHED)"
info "  incident-escalation  ID: $INCIDENT_ESCALATION_DEF_ID  (PUBLISHED)"
info ""
info "Swagger UI:"
info "  identity-service:  $IDENTITY_URL/swagger-ui.html"
info "  workflow-service:  $WORKFLOW_URL/swagger-ui.html"
info "============================================================"
