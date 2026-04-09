# Plan 5: Integration, Demos & Polish

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Wire up demo workflows, create a seed script, add the project README with architecture diagram, and verify the full system works end-to-end via Docker Compose.

**Architecture:** Demo workflows registered as JSON definitions, seed script using curl against live APIs, README as the project's front door for GitHub.

**Tech Stack:** Shell scripting, Markdown, Docker Compose, curl

**Depends on:** Plans 1-4 (all services built)

**Produces:** A fully runnable demo with one-command startup, documented architecture, and showcase-ready README.

---

## Task 1: Demo Workflow Definitions

**Files to create:**
- `examples/workflows/order-fulfillment.json`
- `examples/workflows/incident-escalation.json`

### Step 1.1 — `examples/workflows/order-fulfillment.json`

5-step e-commerce saga with compensation mappings. Each step is `INTERNAL_COMMAND`. Compensation is defined for the three steps with side effects; `validate-order` and `send-notification` have no compensations by design.

```json
{
  "name": "order-fulfillment",
  "description": "E-commerce order fulfillment saga: validates order, reserves inventory, charges payment, creates shipment, and notifies customer. Supports compensation on failure.",
  "version": 1,
  "steps": [
    {
      "step_id": "validate-order",
      "name": "Validate Order",
      "type": "INTERNAL_COMMAND",
      "executor": "validate-order-executor",
      "input_schema": {
        "type": "object",
        "properties": {
          "order_id":    { "type": "string" },
          "customer_id": { "type": "string" },
          "items":       { "type": "array" },
          "failure_config": { "type": "object" }
        },
        "required": ["order_id", "customer_id", "items"]
      },
      "retry_policy": {
        "max_attempts": 3,
        "backoff": "EXPONENTIAL",
        "initial_interval_ms": 1000,
        "multiplier": 2.0,
        "max_interval_ms": 10000
      },
      "timeout_ms": 30000
    },
    {
      "step_id": "reserve-inventory",
      "name": "Reserve Inventory",
      "type": "INTERNAL_COMMAND",
      "executor": "reserve-inventory-executor",
      "input_schema": {
        "type": "object",
        "properties": {
          "order_id": { "type": "string" },
          "items":    { "type": "array" },
          "failure_config": { "type": "object" }
        },
        "required": ["order_id", "items"]
      },
      "retry_policy": {
        "max_attempts": 3,
        "backoff": "EXPONENTIAL",
        "initial_interval_ms": 1000,
        "multiplier": 2.0,
        "max_interval_ms": 10000
      },
      "timeout_ms": 30000
    },
    {
      "step_id": "charge-payment",
      "name": "Charge Payment",
      "type": "INTERNAL_COMMAND",
      "executor": "charge-payment-executor",
      "input_schema": {
        "type": "object",
        "properties": {
          "order_id":       { "type": "string" },
          "customer_id":    { "type": "string" },
          "amount_cents":   { "type": "integer" },
          "currency":       { "type": "string" },
          "failure_config": { "type": "object" }
        },
        "required": ["order_id", "customer_id", "amount_cents", "currency"]
      },
      "retry_policy": {
        "max_attempts": 3,
        "backoff": "EXPONENTIAL",
        "initial_interval_ms": 2000,
        "multiplier": 2.0,
        "max_interval_ms": 15000
      },
      "timeout_ms": 45000
    },
    {
      "step_id": "create-shipment",
      "name": "Create Shipment",
      "type": "INTERNAL_COMMAND",
      "executor": "create-shipment-executor",
      "input_schema": {
        "type": "object",
        "properties": {
          "order_id":          { "type": "string" },
          "shipping_address":  { "type": "object" },
          "items":             { "type": "array" },
          "failure_config":    { "type": "object" }
        },
        "required": ["order_id", "shipping_address", "items"]
      },
      "retry_policy": {
        "max_attempts": 3,
        "backoff": "EXPONENTIAL",
        "initial_interval_ms": 2000,
        "multiplier": 2.0,
        "max_interval_ms": 15000
      },
      "timeout_ms": 60000
    },
    {
      "step_id": "send-notification",
      "name": "Send Notification",
      "type": "INTERNAL_COMMAND",
      "executor": "send-notification-executor",
      "input_schema": {
        "type": "object",
        "properties": {
          "order_id":    { "type": "string" },
          "customer_id": { "type": "string" },
          "channel":     { "type": "string", "enum": ["EMAIL", "SMS", "PUSH"] },
          "failure_config": { "type": "object" }
        },
        "required": ["order_id", "customer_id", "channel"]
      },
      "retry_policy": {
        "max_attempts": 2,
        "backoff": "FIXED",
        "initial_interval_ms": 2000
      },
      "timeout_ms": 20000
    }
  ],
  "compensations": {
    "reserve-inventory": {
      "step_id": "release-inventory",
      "name": "Release Inventory",
      "type": "INTERNAL_COMMAND",
      "executor": "release-inventory-executor",
      "retry_policy": {
        "max_attempts": 5,
        "backoff": "EXPONENTIAL",
        "initial_interval_ms": 1000,
        "multiplier": 2.0,
        "max_interval_ms": 30000
      },
      "timeout_ms": 30000
    },
    "charge-payment": {
      "step_id": "refund-payment",
      "name": "Refund Payment",
      "type": "INTERNAL_COMMAND",
      "executor": "refund-payment-executor",
      "retry_policy": {
        "max_attempts": 5,
        "backoff": "EXPONENTIAL",
        "initial_interval_ms": 2000,
        "multiplier": 2.0,
        "max_interval_ms": 30000
      },
      "timeout_ms": 45000
    },
    "create-shipment": {
      "step_id": "cancel-shipment",
      "name": "Cancel Shipment",
      "type": "INTERNAL_COMMAND",
      "executor": "cancel-shipment-executor",
      "retry_policy": {
        "max_attempts": 5,
        "backoff": "EXPONENTIAL",
        "initial_interval_ms": 2000,
        "multiplier": 2.0,
        "max_interval_ms": 30000
      },
      "timeout_ms": 30000
    }
  }
}
```

**Commit message:** `feat(examples): add order-fulfillment workflow definition (5-step saga with compensation)`

### Step 1.2 — `examples/workflows/incident-escalation.json`

4-step incident management workflow. Step 3 is `EVENT_WAIT` with a 60-second timeout; expiry triggers automatic escalation via step 4.

```json
{
  "name": "incident-escalation",
  "description": "Incident escalation workflow: registers incident, assigns on-call engineer, waits for acknowledgment (60s timeout), and escalates if not acknowledged.",
  "version": 1,
  "steps": [
    {
      "step_id": "register-incident",
      "name": "Register Incident",
      "type": "INTERNAL_COMMAND",
      "executor": "register-incident-executor",
      "input_schema": {
        "type": "object",
        "properties": {
          "incident_id":  { "type": "string" },
          "severity":     { "type": "string", "enum": ["P1", "P2", "P3", "P4"] },
          "title":        { "type": "string" },
          "description":  { "type": "string" },
          "service":      { "type": "string" },
          "failure_config": { "type": "object" }
        },
        "required": ["incident_id", "severity", "title", "service"]
      },
      "retry_policy": {
        "max_attempts": 3,
        "backoff": "FIXED",
        "initial_interval_ms": 1000
      },
      "timeout_ms": 15000
    },
    {
      "step_id": "assign-oncall",
      "name": "Assign On-Call Engineer",
      "type": "INTERNAL_COMMAND",
      "executor": "assign-oncall-executor",
      "input_schema": {
        "type": "object",
        "properties": {
          "incident_id": { "type": "string" },
          "severity":    { "type": "string" },
          "service":     { "type": "string" },
          "failure_config": { "type": "object" }
        },
        "required": ["incident_id", "severity", "service"]
      },
      "retry_policy": {
        "max_attempts": 3,
        "backoff": "FIXED",
        "initial_interval_ms": 1000
      },
      "timeout_ms": 15000
    },
    {
      "step_id": "wait-for-acknowledgment",
      "name": "Wait for Acknowledgment",
      "type": "EVENT_WAIT",
      "event_name": "incident.acknowledged",
      "timeout_ms": 60000,
      "timeout_action": "CONTINUE",
      "input_schema": {
        "type": "object",
        "properties": {
          "incident_id": { "type": "string" }
        },
        "required": ["incident_id"]
      }
    },
    {
      "step_id": "escalate-if-timeout",
      "name": "Escalate Incident",
      "type": "INTERNAL_COMMAND",
      "executor": "escalate-incident-executor",
      "input_schema": {
        "type": "object",
        "properties": {
          "incident_id":  { "type": "string" },
          "severity":     { "type": "string" },
          "acknowledged": { "type": "boolean" },
          "failure_config": { "type": "object" }
        },
        "required": ["incident_id", "severity", "acknowledged"]
      },
      "retry_policy": {
        "max_attempts": 3,
        "backoff": "FIXED",
        "initial_interval_ms": 1000
      },
      "timeout_ms": 15000
    }
  ],
  "compensations": {}
}
```

**Commit message:** `feat(examples): add incident-escalation workflow definition (4-step EVENT_WAIT with timeout escalation)`

---

## Task 2: Seed Script

**Files to create:**
- `scripts/seed.sh`

### Step 2.1 — `scripts/seed.sh`

Idempotent bash script. Uses fixed UUIDs for all created resources and idempotency keys for workflow executions. Waits for services to be healthy before proceeding. Registers and publishes both demo workflow definitions. Prints a summary at the end.

```bash
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

ADMIN_TOKEN=$(echo "$LOGIN_RESPONSE" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

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
```

After creating the file, make it executable:

```bash
chmod +x scripts/seed.sh
```

**Commit message:** `feat(scripts): add idempotent seed script — creates demo users, roles, and publishes workflow definitions`

---

## Task 3: Docker Compose Application Services

**Files to modify:**
- `infra/docker-compose.yml` — add 4 application service definitions

### Step 3.1 — Per-service Dockerfiles

Each service needs a Dockerfile. These are simple multi-stage builds using Maven + JDK 25.

**File:** `identity-service/Dockerfile`

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /workspace

# Copy parent POM and module POMs for dependency caching
COPY pom.xml .
COPY common/pom.xml common/
COPY identity-service/pom.xml identity-service/
COPY workflow-service/pom.xml workflow-service/
COPY worker-service/pom.xml worker-service/
COPY audit-service/pom.xml audit-service/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -pl common,identity-service -am -q

# Copy source
COPY common/src common/src
COPY identity-service/src identity-service/src

# Build
RUN mvn clean package -pl common,identity-service -am -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/identity-service/target/atlas-identity-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**File:** `workflow-service/Dockerfile`

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /workspace

COPY pom.xml .
COPY common/pom.xml common/
COPY identity-service/pom.xml identity-service/
COPY workflow-service/pom.xml workflow-service/
COPY worker-service/pom.xml worker-service/
COPY audit-service/pom.xml audit-service/

RUN mvn dependency:go-offline -pl common,workflow-service -am -q

COPY common/src common/src
COPY workflow-service/src workflow-service/src

RUN mvn clean package -pl common,workflow-service -am -DskipTests -q

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/workflow-service/target/atlas-workflow-service-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**File:** `worker-service/Dockerfile`

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /workspace

COPY pom.xml .
COPY common/pom.xml common/
COPY identity-service/pom.xml identity-service/
COPY workflow-service/pom.xml workflow-service/
COPY worker-service/pom.xml worker-service/
COPY audit-service/pom.xml audit-service/

RUN mvn dependency:go-offline -pl common,worker-service -am -q

COPY common/src common/src
COPY worker-service/src worker-service/src

RUN mvn clean package -pl common,worker-service -am -DskipTests -q

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/worker-service/target/atlas-worker-service-*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**File:** `audit-service/Dockerfile`

```dockerfile
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /workspace

COPY pom.xml .
COPY common/pom.xml common/
COPY identity-service/pom.xml identity-service/
COPY workflow-service/pom.xml workflow-service/
COPY worker-service/pom.xml worker-service/
COPY audit-service/pom.xml audit-service/

RUN mvn dependency:go-offline -pl common,audit-service -am -q

COPY common/src common/src
COPY audit-service/src audit-service/src

RUN mvn clean package -pl common,audit-service -am -DskipTests -q

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/audit-service/target/atlas-audit-service-*.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Step 3.2 — `.env` template

**File:** `infra/.env.example`

```dotenv
# PostgreSQL
POSTGRES_USER=atlas
POSTGRES_PASSWORD=atlas_secret
POSTGRES_DB=atlas

# Atlas JWT signing key (min 32 chars)
ATLAS_JWT_SECRET=atlas-demo-secret-key-change-in-production-min32chars

# Application database URL (used by all services)
ATLAS_DB_URL=jdbc:postgresql://postgres:5432/atlas
ATLAS_DB_USERNAME=atlas
ATLAS_DB_PASSWORD=atlas_secret

# Kafka bootstrap
ATLAS_KAFKA_BOOTSTRAP=kafka:9092

# Redis
ATLAS_REDIS_HOST=redis
ATLAS_REDIS_PORT=6379

# Identity Service internal endpoint (used by other services for permission cache bootstrap)
ATLAS_IDENTITY_URL=http://identity-service:8081
```

### Step 3.3 — Updated `infra/docker-compose.yml`

Replace the existing file entirely with the version below, which adds 4 application services under an `app` profile and a `seed` service under a `seed` profile. Infrastructure services remain unchanged and have no profile (always started).

```yaml
services:

  # ============================================================
  # Infrastructure (always started — no profile)
  # ============================================================

  postgres:
    image: postgres:17
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./postgres/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 10s

  kafka:
    image: confluentinc/cp-kafka:8.2.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk"
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka:9093"
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_LOG_DIRS: /var/lib/kafka/data
    volumes:
      - kafka_data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics --bootstrap-server localhost:9092 --list > /dev/null 2>&1"]
      interval: 15s
      timeout: 10s
      retries: 10
      start_period: 30s

  kafka-init:
    image: confluentinc/cp-kafka:8.2.0
    depends_on:
      kafka:
        condition: service_healthy
    volumes:
      - ./kafka/create-topics.sh:/create-topics.sh:ro
    entrypoint: ["/bin/bash", "/create-topics.sh"]
    restart: on-failure

  redis:
    image: redis:8-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 5s

  prometheus:
    image: prom/prometheus:v3.11.0
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/alerts.yml:/etc/prometheus/alerts.yml:ro
      - prometheus_data:/prometheus
    command:
      - "--config.file=/etc/prometheus/prometheus.yml"
      - "--storage.tsdb.path=/prometheus"
      - "--web.console.libraries=/usr/share/prometheus/console_libraries"
      - "--web.console.templates=/usr/share/prometheus/consoles"
      - "--web.enable-lifecycle"
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_healthy
      redis:
        condition: service_healthy

  grafana:
    image: grafana/grafana:12.4.2
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
      GF_AUTH_ANONYMOUS_ENABLED: "true"
      GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer
    volumes:
      - grafana_data:/var/lib/grafana
      - ./grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards:ro
      - ./grafana/provisioning/datasources:/etc/grafana/provisioning/datasources:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
    depends_on:
      - prometheus
      - tempo

  tempo:
    image: grafana/tempo:2.10.3
    ports:
      - "3200:3200"
      - "4317:4317"
      - "4318:4318"
    volumes:
      - ./tempo/tempo.yml:/etc/tempo/tempo.yml:ro
      - tempo_data:/var/tempo
    command: ["-config.file=/etc/tempo/tempo.yml"]

  # ============================================================
  # Application Services (profile: app)
  # ============================================================

  identity-service:
    profiles: ["app"]
    build:
      context: ..
      dockerfile: identity-service/Dockerfile
    image: atlas/identity-service:latest
    ports:
      - "8081:8081"
    environment:
      SPRING_DATASOURCE_URL: ${ATLAS_DB_URL}
      SPRING_DATASOURCE_USERNAME: ${ATLAS_DB_USERNAME}
      SPRING_DATASOURCE_PASSWORD: ${ATLAS_DB_PASSWORD}
      SPRING_KAFKA_BOOTSTRAP_SERVERS: ${ATLAS_KAFKA_BOOTSTRAP}
      ATLAS_JWT_SECRET: ${ATLAS_JWT_SECRET}
      SPRING_PROFILES_ACTIVE: docker
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_healthy
      kafka-init:
        condition: service_completed_successfully
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8081/actuator/health | grep -q '\"status\":\"UP\"'"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 45s
    restart: on-failure

  workflow-service:
    profiles: ["app"]
    build:
      context: ..
      dockerfile: workflow-service/Dockerfile
    image: atlas/workflow-service:latest
    ports:
      - "8082:8082"
    environment:
      SPRING_DATASOURCE_URL: ${ATLAS_DB_URL}
      SPRING_DATASOURCE_USERNAME: ${ATLAS_DB_USERNAME}
      SPRING_DATASOURCE_PASSWORD: ${ATLAS_DB_PASSWORD}
      SPRING_KAFKA_BOOTSTRAP_SERVERS: ${ATLAS_KAFKA_BOOTSTRAP}
      SPRING_DATA_REDIS_HOST: ${ATLAS_REDIS_HOST}
      SPRING_DATA_REDIS_PORT: ${ATLAS_REDIS_PORT}
      ATLAS_JWT_SECRET: ${ATLAS_JWT_SECRET}
      ATLAS_IDENTITY_URL: ${ATLAS_IDENTITY_URL}
      SPRING_PROFILES_ACTIVE: docker
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_healthy
      kafka-init:
        condition: service_completed_successfully
      redis:
        condition: service_healthy
      identity-service:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8082/actuator/health | grep -q '\"status\":\"UP\"'"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 60s
    restart: on-failure

  worker-service:
    profiles: ["app"]
    build:
      context: ..
      dockerfile: worker-service/Dockerfile
    image: atlas/worker-service:latest
    ports:
      - "8083:8083"
    environment:
      SPRING_KAFKA_BOOTSTRAP_SERVERS: ${ATLAS_KAFKA_BOOTSTRAP}
      SPRING_DATA_REDIS_HOST: ${ATLAS_REDIS_HOST}
      SPRING_DATA_REDIS_PORT: ${ATLAS_REDIS_PORT}
      ATLAS_JWT_SECRET: ${ATLAS_JWT_SECRET}
      ATLAS_IDENTITY_URL: ${ATLAS_IDENTITY_URL}
      SPRING_PROFILES_ACTIVE: docker
    depends_on:
      kafka:
        condition: service_healthy
      kafka-init:
        condition: service_completed_successfully
      redis:
        condition: service_healthy
      identity-service:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8083/actuator/health | grep -q '\"status\":\"UP\"'"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 45s
    restart: on-failure

  audit-service:
    profiles: ["app"]
    build:
      context: ..
      dockerfile: audit-service/Dockerfile
    image: atlas/audit-service:latest
    ports:
      - "8084:8084"
    environment:
      SPRING_DATASOURCE_URL: ${ATLAS_DB_URL}
      SPRING_DATASOURCE_USERNAME: ${ATLAS_DB_USERNAME}
      SPRING_DATASOURCE_PASSWORD: ${ATLAS_DB_PASSWORD}
      SPRING_KAFKA_BOOTSTRAP_SERVERS: ${ATLAS_KAFKA_BOOTSTRAP}
      ATLAS_JWT_SECRET: ${ATLAS_JWT_SECRET}
      ATLAS_IDENTITY_URL: ${ATLAS_IDENTITY_URL}
      SPRING_PROFILES_ACTIVE: docker
    depends_on:
      postgres:
        condition: service_healthy
      kafka:
        condition: service_healthy
      kafka-init:
        condition: service_completed_successfully
      identity-service:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8084/actuator/health | grep -q '\"status\":\"UP\"'"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 45s
    restart: on-failure

volumes:
  postgres_data:
  kafka_data:
  redis_data:
  prometheus_data:
  grafana_data:
  tempo_data:
```

**Commit message:** `feat(infra): add application service definitions to docker-compose with app profile; add per-service Dockerfiles`

---

## Task 4: Makefile

**Files to create:**
- `Makefile` (at project root)

### Step 4.1 — `Makefile`

```makefile
.DEFAULT_GOAL := help
COMPOSE_FILE  := infra/docker-compose.yml
ENV_FILE      := infra/.env

# ============================================================
# Help
# ============================================================

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| sort \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ============================================================
# Build
# ============================================================

.PHONY: build
build: ## Build all modules (skip tests)
	mvn clean package -DskipTests

.PHONY: build-docker
build-docker: ## Build all Docker images (requires infra/.env)
	docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE) \
		--profile app build

.PHONY: test
test: ## Run all tests
	mvn test

.PHONY: clean
clean: ## Clean build artifacts
	mvn clean

# ============================================================
# Infrastructure
# ============================================================

.PHONY: infra-up
infra-up: _check-env ## Start infrastructure services (postgres, kafka, redis, observability)
	docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE) up -d
	@echo ""
	@echo "Infrastructure started. Services:"
	@echo "  PostgreSQL  → localhost:5432"
	@echo "  Kafka       → localhost:9092"
	@echo "  Redis       → localhost:6379"
	@echo "  Prometheus  → http://localhost:9090"
	@echo "  Grafana     → http://localhost:3000  (admin/admin)"
	@echo "  Tempo       → http://localhost:3200"

.PHONY: infra-down
infra-down: ## Stop and remove infrastructure containers
	docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE) down

.PHONY: infra-logs
infra-logs: ## Tail infrastructure logs
	docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE) logs -f

# ============================================================
# Application
# ============================================================

.PHONY: app-up
app-up: _check-env ## Start all application services (requires build-docker first)
	docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE) \
		--profile app up -d
	@echo ""
	@echo "Application services started:"
	@echo "  identity-service  → http://localhost:8081"
	@echo "  workflow-service  → http://localhost:8082"
	@echo "  worker-service    → http://localhost:8083"
	@echo "  audit-service     → http://localhost:8084"

.PHONY: app-down
app-down: ## Stop application services
	docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE) \
		--profile app down

.PHONY: app-logs
app-logs: ## Tail application service logs
	docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE) \
		--profile app logs -f identity-service workflow-service worker-service audit-service

# ============================================================
# All-in-one
# ============================================================

.PHONY: up
up: _check-env build-docker ## Build images + start everything (infra + app)
	docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE) \
		--profile app up -d
	@echo ""
	@echo "Atlas is starting. Run 'make seed' once services are healthy."

.PHONY: down
down: ## Stop and remove all containers (infra + app)
	docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE) \
		--profile app down

.PHONY: seed
seed: ## Seed demo users and workflow definitions (requires services running)
	./scripts/seed.sh

# ============================================================
# Utilities
# ============================================================

.PHONY: ps
ps: ## Show running containers
	docker compose -f $(COMPOSE_FILE) --env-file $(ENV_FILE) \
		--profile app ps

.PHONY: health
health: ## Check health of all application services
	@echo "identity-service:"; \
		curl -sf http://localhost:8081/actuator/health | python3 -m json.tool 2>/dev/null || echo "  NOT REACHABLE"
	@echo "workflow-service:"; \
		curl -sf http://localhost:8082/actuator/health | python3 -m json.tool 2>/dev/null || echo "  NOT REACHABLE"
	@echo "worker-service:"; \
		curl -sf http://localhost:8083/actuator/health | python3 -m json.tool 2>/dev/null || echo "  NOT REACHABLE"
	@echo "audit-service:"; \
		curl -sf http://localhost:8084/actuator/health | python3 -m json.tool 2>/dev/null || echo "  NOT REACHABLE"

.PHONY: setup-env
setup-env: ## Copy .env.example to infra/.env (first-time setup)
	@if [ -f $(ENV_FILE) ]; then \
		echo "$(ENV_FILE) already exists. Remove it first to reset."; \
	else \
		cp infra/.env.example $(ENV_FILE); \
		echo "Created $(ENV_FILE) from template. Review and adjust values if needed."; \
	fi

.PHONY: _check-env
_check-env:
	@if [ ! -f $(ENV_FILE) ]; then \
		echo "ERROR: $(ENV_FILE) not found. Run 'make setup-env' first."; \
		exit 1; \
	fi
```

**Commit message:** `feat: add Makefile with build, infra, app, seed, and health targets`

---

## Task 5: Project README

**Files to create:**
- `README.md` (at project root)

### Step 5.1 — `README.md`

```markdown
# Atlas

**A production-grade distributed workflow orchestration platform** — built to demonstrate real-world backend engineering: multi-tenancy, saga compensation, event-driven architecture, CQRS, and full observability stack.

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kafka](https://img.shields.io/badge/Kafka-KRaft-black.svg)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue.svg)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-8-red.svg)](https://redis.io/)
[![License](https://img.shields.io/badge/license-MIT-lightgrey.svg)](LICENSE)

---

## What Is Atlas?

Atlas is a **workflow orchestration engine** that lets tenants define multi-step workflows as JSON, execute them with at-least-once delivery guarantees, and automatically compensate (roll back) completed steps when a downstream step fails permanently.

It is a portfolio project built to depth — four microservices, full observability (Prometheus + Grafana + Tempo), and a one-command Docker Compose setup.

---

## Architecture

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

---

## Key Features

- **Multi-tenant from the ground up** — every row carries `tenant_id`, enforced by Hibernate `@Filter` and Spring Security
- **Saga orchestration with compensation** — the Workflow Service drives execution order and triggers compensating steps in reverse when a step fails permanently
- **Transactional outbox pattern** — Kafka messages are written atomically with DB state; no message is lost even if Kafka is down
- **At-least-once delivery with idempotent handlers** — duplicate events are safely discarded via deduplication keys
- **Redis-backed distributed leases** — workers hold a lease while executing a step; stale leases (worker crash) are detected by a timeout scanner and retried automatically
- **EVENT_WAIT steps** — workflows can pause waiting for an external signal with a configurable timeout and automatic escalation
- **Full audit trail** — every state transition is emitted as an audit event and queryable via the Audit Service
- **RBAC** — fine-grained permissions (`workflow.read`, `workflow.execute`, `workflow.manage`, `tenant.manage`, `audit.read`) bundled into roles
- **Three-pillar observability** — Prometheus metrics, structured JSON logs (Logback), and distributed traces (Brave + Tempo), all correlated by `X-Correlation-ID`
- **OpenAPI / Swagger UI** — each service exposes interactive docs at `/swagger-ui.html`

---

## Quickstart

```bash
# 1. Prerequisites: Docker, Docker Compose v2, make, jq
# 2. Clone and enter the repo
git clone https://github.com/OriolJT/atlas.git && cd atlas

# 3. Create your local env file
make setup-env

# 4. Build Docker images and start everything
make up

# 5. Wait ~60s for services to initialise, then seed demo data
make seed
```

That's it. Atlas is now running with two published workflow definitions and three demo users.

### Access Points

| Service | URL |
|---------|-----|
| Identity Service API | http://localhost:8081/swagger-ui.html |
| Workflow Service API | http://localhost:8082/swagger-ui.html |
| Worker Service (metrics only) | http://localhost:8083/actuator/health |
| Audit Service API | http://localhost:8084/swagger-ui.html |
| Grafana (admin/admin) | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

---

## Demo Scenarios

### Scenario A — Happy Path: Order Fulfillment

```bash
# 1. Login as operator
TOKEN=$(curl -sf -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"operator@acme.com","password":"Atlas2026!"}' \
  | jq -r '.access_token')

# 2. Start an execution
curl -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-order-001" \
  -d '{
    "definition_id": "c0000000-0000-0000-0000-000000000001",
    "input": {
      "order_id": "ORD-001",
      "customer_id": "CUST-001",
      "items": [{"sku": "WIDGET-A", "qty": 2}],
      "amount_cents": 4999,
      "currency": "EUR",
      "channel": "EMAIL",
      "shipping_address": {
        "street": "Kaiserstrasse 10",
        "city": "Frankfurt",
        "country": "DE"
      }
    }
  }'

# 3. Poll status until COMPLETED
EXEC_ID=<id from response above>
curl -sf http://localhost:8082/api/v1/workflow-executions/$EXEC_ID \
  -H "Authorization: Bearer $TOKEN" | jq '.status'

# 4. View the full timeline
curl -sf http://localhost:8082/api/v1/workflow-executions/$EXEC_ID/timeline \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### Scenario B — Failure + Compensation

```bash
# Start an execution with failure injection on create-shipment
curl -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-order-002-fail" \
  -d '{
    "definition_id": "c0000000-0000-0000-0000-000000000001",
    "input": {
      "order_id": "ORD-002",
      "customer_id": "CUST-001",
      "items": [{"sku": "WIDGET-B", "qty": 1}],
      "amount_cents": 2999,
      "currency": "EUR",
      "channel": "EMAIL",
      "shipping_address": { "street": "Main St 1", "city": "Berlin", "country": "DE" },
      "failure_config": {
        "fail_at_step": "create-shipment",
        "failure_type": "PERMANENT"
      }
    }
  }'
# Watch the execution reach COMPENSATED. The timeline will show refund-payment
# and release-inventory ran in reverse order.
```

### Scenario C — Incident Escalation with EVENT_WAIT

```bash
# Start an incident escalation
curl -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-incident-001" \
  -d '{
    "definition_id": "c0000000-0000-0000-0000-000000000002",
    "input": {
      "incident_id": "INC-001",
      "severity": "P1",
      "title": "Database connection pool exhausted",
      "service": "payments-service",
      "acknowledged": false
    }
  }'

EXEC_ID=<id from response>

# Option A: Acknowledge within 60s (prevents escalation)
curl -X POST http://localhost:8082/api/v1/workflow-executions/$EXEC_ID/signal \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"event_name": "incident.acknowledged", "payload": {"acknowledged_by": "alice@acme.com"}}'

# Option B: Do nothing — after 60s the timeout fires and escalate-if-timeout runs automatically.
```

### Scenario D — RBAC Demo

```bash
# Login as viewer (read-only role)
VIEWER_TOKEN=$(curl -sf -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"viewer@acme.com","password":"Atlas2026!"}' \
  | jq -r '.access_token')

# Can read executions — returns 200
curl -sf http://localhost:8082/api/v1/workflow-executions/$EXEC_ID \
  -H "Authorization: Bearer $VIEWER_TOKEN"

# Cannot start executions — returns 403 ATLAS-AUTH-004
curl -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $VIEWER_TOKEN" \
  -H "Idempotency-Key: demo-viewer-blocked" \
  -d '{"definition_id":"c0000000-0000-0000-0000-000000000001","input":{}}'
```

---

## API Overview

### Identity Service (`:8081`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/auth/login` | None | Issue JWT + refresh token |
| POST | `/api/v1/auth/refresh` | None | Rotate refresh token |
| POST | `/api/v1/auth/logout` | JWT | Revoke refresh token |
| POST | `/api/v1/users` | JWT + `tenant.manage` | Create user |
| GET | `/api/v1/users/{id}` | JWT | Get user |
| POST | `/api/v1/users/{id}/roles` | JWT + `tenant.manage` | Assign role to user |
| POST | `/api/v1/roles` | JWT + `tenant.manage` | Create role |
| POST | `/api/v1/roles/{id}/permissions` | JWT + `tenant.manage` | Assign permissions |
| POST | `/api/v1/tenants` | JWT + `tenant.manage` | Create tenant |
| GET | `/api/v1/tenants/{id}` | JWT | Get tenant |
| POST | `/api/v1/service-accounts` | JWT + `tenant.manage` | Create service account |
| POST | `/api/v1/api-keys` | JWT + `tenant.manage` | Create API key |
| GET | `/api/v1/internal/permissions` | Service-to-service | Fetch role-permission map |
| GET | `/actuator/health` | None | Health check |

### Workflow Service (`:8082`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/workflow-definitions` | JWT + `workflow.manage` | Register definition (DRAFT) |
| GET | `/api/v1/workflow-definitions/{id}` | JWT + `workflow.read` | Get definition |
| POST | `/api/v1/workflow-definitions/{id}/publish` | JWT + `workflow.manage` | Publish definition |
| POST | `/api/v1/workflow-executions` | JWT + `workflow.execute` | Start execution |
| GET | `/api/v1/workflow-executions/{id}` | JWT + `workflow.read` | Get execution status |
| POST | `/api/v1/workflow-executions/{id}/cancel` | JWT + `workflow.manage` | Cancel execution |
| POST | `/api/v1/workflow-executions/{id}/signal` | JWT + `workflow.execute` | Send signal to EVENT_WAIT |
| GET | `/api/v1/workflow-executions/{id}/timeline` | JWT + `workflow.read` | Get execution timeline |
| GET | `/api/v1/dead-letter` | JWT + `workflow.manage` | List dead-letter items |
| POST | `/api/v1/dead-letter/{id}/replay` | JWT + `workflow.manage` | Replay dead-letter item |
| GET | `/actuator/health` | None | Health check |

### Audit Service (`:8084`)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/audit-events` | JWT + `audit.read` | Query audit events (paginated) |
| GET | `/actuator/health` | None | Health check |

---

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Java 25 (Virtual Threads) |
| Framework | Spring Boot 4.0 |
| Persistence | Spring Data JPA, Flyway, PostgreSQL 17 |
| Messaging | Apache Kafka (KRaft mode), Spring Kafka |
| Caching / Leases | Redis 8, Spring Data Redis |
| Security | Spring Security, JWT (HS256), BCrypt |
| Observability | Micrometer, Prometheus, Grafana, Brave, Tempo |
| Build | Maven 3 (multi-module) |
| Testing | JUnit 5, Testcontainers 2 |
| Containerisation | Docker, Docker Compose v2 |

---

## Project Structure

```
atlas/
├── common/                    # Shared module: DTOs, JWT utils, tenant context, outbox entity
├── identity-service/          # Port 8081 — auth, tenant management, RBAC
├── workflow-service/          # Port 8082 — definition registry, execution engine, state machine
├── worker-service/            # Port 8083 — step executor, Redis lease, failure injection
├── audit-service/             # Port 8084 — append-only audit log, query API
├── infra/
│   ├── docker-compose.yml     # Full stack: infra + app services
│   ├── postgres/init.sql      # Creates per-service schemas
│   ├── kafka/create-topics.sh # Topic provisioning on Kafka start
│   ├── prometheus/            # Prometheus config + alert rules
│   ├── grafana/               # Dashboard provisioning JSON
│   └── tempo/                 # Tempo tracing config
├── scripts/
│   └── seed.sh                # Idempotent seed: users, roles, workflow definitions
├── examples/
│   └── workflows/
│       ├── order-fulfillment.json
│       └── incident-escalation.json
├── docs/
│   ├── architecture.md        # Architecture deep-dive
│   ├── api.md                 # Full API reference
│   ├── tradeoffs.md           # Engineering decisions and rationale
│   ├── demo-guide.md          # Step-by-step demo walkthrough
│   └── local-setup.md         # Prerequisites and setup guide
└── Makefile                   # Build, infra, app, seed targets
```

---

## Testing

```bash
# Run all tests (unit + integration via Testcontainers)
make test

# Run tests for a single module
mvn test -pl identity-service

# Run a specific test class
mvn test -pl workflow-service -Dtest=WorkflowStateMachineTest
```

Integration tests use Testcontainers to spin up real PostgreSQL, Kafka, and Redis instances. No mocking of infrastructure.

Key test scenarios:
- JWT authentication and token rotation
- Tenant isolation (cross-tenant access returns 404, not 403)
- RBAC enforcement (viewer blocked from execution)
- Workflow state machine — all valid and invalid transitions
- Compensation ordering — compensation runs in reverse of completion order
- Idempotency — duplicate idempotency key returns 409
- Outbox reliability — Kafka down, events published after recovery
- Worker crash recovery — stale lease detected, step retried
- Duplicate result delivery — deduplicated, state advances once

---

## Documented Tradeoffs

See [docs/tradeoffs.md](docs/tradeoffs.md) for the full list of design decisions and their rationale.

Quick summary:
- **At-least-once over exactly-once** — simpler, achievable with idempotent handlers
- **Orchestration over choreography** — centralized state makes compensation and debugging tractable
- **Shared DB with tenant scoping** — sufficient isolation for v1, evolvable to schema-per-tenant
- **Redis for leases** — ephemeral by design; Redis failure causes delays, not data loss
- **HS256 for JWT** — simpler key management for v1; evolvable to RS256

---

## License

MIT — see [LICENSE](LICENSE).
```

**Commit message:** `docs: add project README with architecture diagram, quickstart, demo scenarios, and API overview`

---

## Task 6: Documentation Files

**Files to create:**
- `docs/architecture.md`
- `docs/api.md`
- `docs/tradeoffs.md`
- `docs/demo-guide.md`
- `docs/local-setup.md`

### Step 6.1 — `docs/architecture.md`

```markdown
# Atlas Architecture

## Overview

Atlas is a multi-tenant workflow orchestration platform built as four independent Spring Boot services. The architecture is orchestration-first: the Workflow Service owns execution state, schedules steps, and drives the Worker Service via Kafka commands. Workers are stateless executors.

## Services

| Service | Port | Schema | Responsibility |
|---------|------|--------|----------------|
| identity-service | 8081 | `identity` | Authentication, multi-tenancy, RBAC, token lifecycle |
| workflow-service | 8082 | `workflow` | Workflow definitions, execution engine, state machine, outbox |
| worker-service | 8083 | — | Step execution, Redis lease management, failure injection |
| audit-service | 8084 | `audit` | Append-only audit log, paginated query API |

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Framework | Spring Boot 4 | Broad ecosystem, virtual threads (Java 25), production-proven |
| Architecture | Orchestration-first, 4 services | Centralized state, clean saga compensation, tractable debugging |
| Database | Shared PostgreSQL, separate schemas | Enforces data ownership; lean infra for a portfolio project |
| Messaging | Kafka KRaft + outbox pattern | At-least-once delivery with transactional consistency |
| Caching/Leases | Redis | Ephemeral lease management; Redis failure = delays, not data loss |
| Auth | JWT HS256 + BCrypt + RBAC | Simple symmetric signing; evolvable to RS256 |
| Multi-tenancy | `tenant_id` on all rows + Hibernate `@Filter` | Virtual-thread safe double guard |
| Observability | Micrometer + Prometheus + Grafana + Brave + Tempo | All three pillars correlated by `X-Correlation-ID` |

## Communication Patterns

- **Synchronous REST** — external API, service-to-service permission cache bootstrap
- **Asynchronous Kafka** — step execution commands, results, audit events, domain events
- **Outbox-backed publishing** — all Kafka messages from Identity and Workflow services go through the outbox, ensuring atomic consistency with DB state

## Execution Flow

1. Client calls `POST /api/v1/workflow-executions` on Workflow Service with a JWT and idempotency key
2. Workflow Service writes: execution row (`PENDING`) + first step row (`PENDING`) + outbox row — in one transaction
3. Returns `202 Accepted` with the execution ID
4. Outbox poller publishes `steps.execute` to Kafka
5. Worker Service consumes, acquires a Redis lease (SET NX with TTL), executes the step logic
6. Worker publishes `steps.result` to Kafka with success or failure
7. Workflow Service consumes, deduplicates by `(step_execution_id, attempt_count)`, advances state machine
8. If step succeeded and more steps remain: write next step + outbox row in one transaction, repeat from step 4
9. If step succeeded and no more steps: mark execution `COMPLETED`
10. If step failed and retries remain: schedule retry via Redis delay
11. If step failed permanently: trigger compensation in reverse completion order
12. All transitions emit audit events via outbox → `audit.events` topic → Audit Service

## Multi-Tenancy

Every database row across all services carries a `tenant_id`. Isolation is enforced at three layers:

1. **JWT** — `tenant_id` extracted from token on every request
2. **Hibernate `@Filter`** — `WHERE tenant_id = :tenantId` injected into all JPA queries
3. **Repository layer** — explicit `tenant_id` in all repository method signatures

Cross-tenant access returns `404 Not Found`, not `403 Forbidden` — tenants should not know each other exist.

## Compensation (Saga)

When a step fails permanently (retries exhausted, non-retryable error):

1. Execution transitions to `COMPENSATING`
2. Compensation engine queries steps with status `SUCCEEDED`, ordered by `finished_at DESC`
3. For each succeeded step that has a compensation defined: publishes compensation step to Kafka
4. Compensation steps are executed by the same worker infrastructure with their own retry policies
5. On all compensations complete: execution transitions to `COMPENSATED`
6. On any compensation step exhausting retries: execution transitions to `COMPENSATION_FAILED` — dead-lettered, requires operator intervention

Compensation is best-effort but durable: failures are recorded and observable.

## Failure Recovery

| Failure | Recovery |
|---------|----------|
| Worker crashes mid-step | Redis lease expires; timeout scanner detects stale LEASED step; retries automatically |
| Kafka unavailable | Outbox accumulates rows; published in-order when Kafka returns |
| Duplicate message delivery | `(step_execution_id, attempt_count)` deduplication; idempotent inserts in Audit Service |
| Redis unavailable | Workers cannot acquire leases; timeout scanner retries until Redis returns |
| Compensation step fails | Dead-lettered; execution enters `COMPENSATION_FAILED`; operator can replay |
```

### Step 6.2 — `docs/api.md`

```markdown
# Atlas API Reference

All services prefix endpoints with `/api/v1/`. All requests require `Authorization: Bearer <jwt>` unless noted. All responses and errors use the `ErrorResponse` structure described below.

## Error Response Structure

```json
{
  "code": "ATLAS-WF-002",
  "message": "Workflow definition is not published",
  "details": "Definition 'order-fulfillment' v1 is in DRAFT state.",
  "correlation_id": "abc123",
  "timestamp": "2026-04-08T14:32:01.123Z"
}
```

Validation errors include a nested `errors` array with field-level messages.

## Error Code Taxonomy

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
| `ATLAS-COMMON-001` | Any | Validation failed |
| `ATLAS-COMMON-002` | Any | Tenant scope mismatch |

---

## Identity Service — `http://localhost:8081`

### POST `/api/v1/auth/login`
No auth required. Issues JWT access token (15 min) and refresh token (7 days).

**Request:**
```json
{ "email": "operator@acme.com", "password": "Atlas2026!" }
```
**Response `200`:**
```json
{ "access_token": "eyJ...", "refresh_token": "rtk_...", "expires_in": 900 }
```

### POST `/api/v1/auth/refresh`
No auth required. Rotates refresh token.

**Request:** `{ "refresh_token": "rtk_..." }`
**Response `200`:** Same structure as login.

### POST `/api/v1/auth/logout`
Requires JWT. Revokes the current refresh token.

**Request:** `{ "refresh_token": "rtk_..." }`
**Response `204`:** No content.

### POST `/api/v1/users`
Requires `tenant.manage`. Creates a new user in the caller's tenant.

**Request:**
```json
{
  "user_id": "optional-fixed-uuid",
  "email": "newuser@acme.com",
  "password": "SecurePass1!",
  "first_name": "New",
  "last_name": "User"
}
```
**Response `201`:** Created user object (no password hash).

### GET `/api/v1/users/{id}`
Requires JWT. Returns user details for users within the caller's tenant.

### POST `/api/v1/users/{id}/roles`
Requires `tenant.manage`. Assigns a role to a user.

**Request:** `{ "role_id": "a0000000-0000-0000-0000-000000000031" }`
**Response `204`:** No content.

### POST `/api/v1/roles`
Requires `tenant.manage`. Creates a new role.

**Request:**
```json
{ "name": "CUSTOM_ROLE", "description": "Custom role for specific users" }
```
**Response `201`:** Created role object.

### POST `/api/v1/roles/{id}/permissions`
Requires `tenant.manage`. Assigns permissions to a role.

**Request:** `{ "permission_ids": ["10000000-0000-0000-0000-000000000001"] }`
**Response `204`:** No content.

### POST `/api/v1/tenants`
Requires `tenant.manage`. Creates a new tenant (superadmin use).

### GET `/api/v1/tenants/{id}`
Requires JWT. Returns tenant details.

### GET `/api/v1/internal/permissions`
No auth (service-to-service, internal network only). Returns full role-permission map for cache bootstrap.

### GET `/actuator/health`
No auth. Returns `{ "status": "UP" }`.

---

## Workflow Service — `http://localhost:8082`

### POST `/api/v1/workflow-definitions`
Requires `workflow.manage`. Registers a new workflow definition in DRAFT state.

**Request:** Workflow definition JSON (see `examples/workflows/`).
**Response `201`:** Created definition with `status: DRAFT`.

### GET `/api/v1/workflow-definitions/{id}`
Requires `workflow.read`. Returns definition and all steps.

### POST `/api/v1/workflow-definitions/{id}/publish`
Requires `workflow.manage`. Transitions definition from DRAFT to PUBLISHED.

**Response `200`:** Updated definition with `status: PUBLISHED`.

### POST `/api/v1/workflow-executions`
Requires `workflow.execute`. Starts a new execution. Idempotency key required.

**Headers:** `Idempotency-Key: <unique-string>`

**Request:**
```json
{
  "definition_id": "c0000000-0000-0000-0000-000000000001",
  "input": { "order_id": "ORD-001", "..." : "..." }
}
```
**Response `202`:** Execution created with `status: PENDING` and `execution_id`.

### GET `/api/v1/workflow-executions/{id}`
Requires `workflow.read`. Returns execution status, current step, and attempt count.

### POST `/api/v1/workflow-executions/{id}/cancel`
Requires `workflow.manage`. Cancels an active execution (PENDING, RUNNING, or WAITING only).

**Response `200`:** Updated execution with `status: CANCELED`.
**Error `409`:** `ATLAS-WF-007` if execution is in a terminal or non-cancellable state.

### POST `/api/v1/workflow-executions/{id}/signal`
Requires `workflow.execute`. Sends a signal to an EVENT_WAIT step.

**Request:** `{ "event_name": "incident.acknowledged", "payload": {} }`
**Response `200`:** Execution updated.
**Error `409`:** `ATLAS-WF-006` if no step is currently waiting for the given event.

### GET `/api/v1/workflow-executions/{id}/timeline`
Requires `workflow.read`. Returns ordered list of all step executions, attempts, results, and audit events.

### GET `/api/v1/dead-letter`
Requires `workflow.manage`. Returns paginated list of dead-letter items.

**Query params:** `page`, `size`, `status` (PENDING | REPLAYED | RESOLVED)

### POST `/api/v1/dead-letter/{id}/replay`
Requires `workflow.manage`. Re-publishes the dead-letter item's Kafka message.

**Response `202`:** Replay scheduled.

### GET `/actuator/health`
No auth.

---

## Audit Service — `http://localhost:8084`

### GET `/api/v1/audit-events`
Requires `audit.read`. Returns paginated audit events filtered by various criteria.

**Query params:**

| Param | Type | Description |
|-------|------|-------------|
| `entity_type` | string | Filter by entity (e.g. `WORKFLOW_EXECUTION`) |
| `entity_id` | UUID | Filter by specific entity |
| `actor_id` | UUID | Filter by user who performed the action |
| `event_type` | string | Filter by event type (e.g. `EXECUTION_STARTED`) |
| `from` | ISO-8601 | Events after this timestamp |
| `to` | ISO-8601 | Events before this timestamp |
| `cursor` | string | Pagination cursor (opaque, from previous response) |
| `size` | int | Page size (default 20, max 100) |

**Response `200`:**
```json
{
  "data": [
    {
      "audit_event_id": "...",
      "tenant_id": "...",
      "entity_type": "WORKFLOW_EXECUTION",
      "entity_id": "...",
      "event_type": "EXECUTION_STARTED",
      "actor_id": "...",
      "actor_email": "operator@acme.com",
      "occurred_at": "2026-04-08T14:30:00Z",
      "payload": {}
    }
  ],
  "next_cursor": "eyJ...",
  "has_more": true
}
```

### GET `/actuator/health`
No auth.
```

### Step 6.3 — `docs/tradeoffs.md`

```markdown
# Atlas Engineering Tradeoffs

This document records the deliberate tradeoffs made in Atlas and the reasoning behind each decision. Real systems have constraints; engineering means choosing which properties to optimise for.

---

## 1. At-Least-Once Delivery over Exactly-Once

**Decision:** Atlas guarantees at-least-once delivery for all Kafka messages. It does not attempt exactly-once.

**Why:**
Exactly-once delivery in a distributed system requires distributed transactions (e.g., Kafka transactions with `ISOLATION_LEVEL=read_committed` on all consumers). This adds significant operational complexity, constrains consumer configuration, and is disproportionate to the benefit for most workloads.

At-least-once is achievable with simpler infrastructure: the outbox pattern ensures messages are published at least once, and consumers deduplicate by a stable key (`(step_execution_id, attempt_count)` for step results; `ON CONFLICT DO NOTHING` for audit events).

**Consequence:** Consumers must be idempotent. This is enforced and tested. Duplicate processing is safe.

---

## 2. Orchestration over Choreography

**Decision:** The Workflow Service is the central brain. It drives workers by publishing explicit step commands.

**Why:**
Choreography distributes coordination logic across all participants. When compensation or failure recovery is needed, tracing causality through multiple services and event streams is hard. With orchestration, execution state lives in one place (the Workflow Service database), the timeline is reconstructable without log correlation, and compensation order is deterministically computed from a single data source.

**Consequence:** The Workflow Service is the throughput bottleneck. Under extreme load, horizontal scaling of the orchestrator becomes necessary. This is a known and acceptable tradeoff for v1 at portfolio scale.

---

## 3. Shared Database with Tenant Scoping over DB-per-Tenant

**Decision:** All tenants share one PostgreSQL instance. Isolation is enforced via `tenant_id` on every row, Hibernate `@Filter`, and explicit repository predicates.

**Why:**
DB-per-tenant provides the strongest isolation and the simplest application code (no tenant context threading), but requires a connection pool per tenant, schema migration per tenant, and operational overhead that scales with tenant count. For v1, virtual isolation through `tenant_id` is sufficient and significantly simpler to operate.

**Consequence:** A misconfigured query missing `tenant_id` predicate would be a data leak. This risk is mitigated by three layers of enforcement (filter + repository + security) and integration tests that explicitly verify cross-tenant access returns 404.

**Evolvable to:** Schema-per-tenant (one PostgreSQL schema per tenant, switched via `SET search_path`) or DB-per-tenant as the product scales.

---

## 4. Redis for Leases and Delay Scheduling

**Decision:** Worker leases are stored in Redis using SET NX with TTL. Delayed step retries are also scheduled via Redis sorted sets.

**Why:**
Lease management requires an atomic "check and set" operation with automatic expiry — exactly what Redis `SET NX PX` provides. Implementing this in PostgreSQL requires advisory locks or a polling table, both more complex and higher-latency. Redis is the right tool.

**Consequence:** Redis is now a hard dependency for workflow execution. Redis failure means workers cannot acquire leases and executions stall. The timeout scanner detects this and retries automatically when Redis recovers — no data is lost, only latency increases.

---

## 5. Compensation Is Best-Effort but Durable

**Decision:** Compensation steps use the same retry infrastructure as forward steps. If a compensation step exhausts all retries, the execution enters `COMPENSATION_FAILED` and is dead-lettered rather than silently ignored.

**Why:**
Silently ignoring compensation failures leaves side effects in an unknown state (e.g., a payment charged but not refunded). Best-effort-but-visible is strictly better: operators can inspect the dead-letter queue, understand what failed, and decide whether to replay, manually compensate, or escalate.

**Consequence:** `COMPENSATION_FAILED` is a terminal state requiring operator intervention. This is intentional — it is the operationally honest outcome when automated recovery has been exhausted.

---

## 6. Four Services, Not More

**Decision:** Atlas is decomposed into exactly four services (identity, workflow, worker, audit). It is not decomposed further (e.g., separate notification service, separate scheduler service).

**Why:**
Portfolio projects commonly suffer from micro-service sprawl — many thin services with little internal complexity, demonstrating only HTTP wiring. Atlas aims to demonstrate depth. Each service here has a meaningful internal model (state machine, RBAC engine, compensation logic, append-only audit store) that is worth implementing correctly.

**Consequence:** Some concerns that could be separate services (e.g., scheduled retry management) live inside the Workflow Service. This is appropriate for the current scale and scope.

---

## 7. HS256 over RS256 for JWT Signing

**Decision:** All JWTs are signed with a shared HS256 secret. All services validate JWTs locally using the same shared key.

**Why:**
HS256 requires distributing one shared secret. RS256 requires generating a key pair, distributing the public key to all services (via JWKS endpoint or static config), and rotating keys safely. For a four-service system owned by a single team, the added complexity of RS256 does not provide meaningful security improvement.

**Consequence:** The shared signing key must be protected. Rotation requires a restart of all services simultaneously (or a short overlap window with two valid keys). Evolvable to RS256 by adding a JWKS endpoint to the Identity Service and updating each service to fetch the public key on startup.
```

### Step 6.4 — `docs/demo-guide.md`

```markdown
# Atlas Demo Guide

Step-by-step walkthrough of Atlas's two demo workflows. Run `make up && make seed` before starting.

## Prerequisites

- Atlas is running (`make up`)
- Seed script has been run (`make seed`)
- `curl` and `jq` are installed

## Step 1: Authenticate

```bash
# Login as operator (full execution rights)
export TOKEN=$(curl -sf -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"operator@acme.com","password":"Atlas2026!"}' \
  | jq -r '.access_token')

echo "Token obtained: ${TOKEN:0:20}..."
```

---

## Demo 1: Order Fulfillment — Happy Path

All 5 steps succeed. Execution reaches `COMPLETED`.

```bash
# Start execution
EXEC=$(curl -sf -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-order-happy-$(date +%s)" \
  -d '{
    "definition_id": "c0000000-0000-0000-0000-000000000001",
    "input": {
      "order_id": "ORD-001",
      "customer_id": "CUST-001",
      "items": [{"sku": "WIDGET-A", "qty": 2}],
      "amount_cents": 4999,
      "currency": "EUR",
      "channel": "EMAIL",
      "shipping_address": {
        "street": "Kaiserstrasse 10",
        "city": "Frankfurt",
        "country": "DE"
      }
    }
  }')

export EXEC_ID=$(echo $EXEC | jq -r '.execution_id')
echo "Execution started: $EXEC_ID"

# Poll until terminal state
while true; do
  STATUS=$(curl -sf http://localhost:8082/api/v1/workflow-executions/$EXEC_ID \
    -H "Authorization: Bearer $TOKEN" | jq -r '.status')
  echo "Status: $STATUS"
  [[ "$STATUS" == "COMPLETED" || "$STATUS" == "COMPENSATED" || "$STATUS" == "FAILED" ]] && break
  sleep 2
done

# View timeline
curl -sf http://localhost:8082/api/v1/workflow-executions/$EXEC_ID/timeline \
  -H "Authorization: Bearer $TOKEN" | jq '.steps[].step_id, .steps[].status'
```

Expected output: all 5 step IDs with status `SUCCEEDED`, execution `COMPLETED`.

---

## Demo 2: Order Fulfillment — Failure + Compensation

`create-shipment` fails permanently. The compensation engine runs `cancel-shipment` → `refund-payment` → `release-inventory` in reverse order.

```bash
EXEC=$(curl -sf -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-order-fail-$(date +%s)" \
  -d '{
    "definition_id": "c0000000-0000-0000-0000-000000000001",
    "input": {
      "order_id": "ORD-002",
      "customer_id": "CUST-001",
      "items": [{"sku": "WIDGET-B", "qty": 1}],
      "amount_cents": 2999,
      "currency": "EUR",
      "channel": "EMAIL",
      "shipping_address": {"street": "Main St 1", "city": "Berlin", "country": "DE"},
      "failure_config": {
        "fail_at_step": "create-shipment",
        "failure_type": "PERMANENT"
      }
    }
  }')

export EXEC_ID=$(echo $EXEC | jq -r '.execution_id')

# Poll until COMPENSATED
while true; do
  STATUS=$(curl -sf http://localhost:8082/api/v1/workflow-executions/$EXEC_ID \
    -H "Authorization: Bearer $TOKEN" | jq -r '.status')
  echo "Status: $STATUS"
  [[ "$STATUS" == "COMPENSATED" || "$STATUS" == "COMPENSATION_FAILED" ]] && break
  sleep 2
done

# View full timeline showing forward run + compensation
curl -sf http://localhost:8082/api/v1/workflow-executions/$EXEC_ID/timeline \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Expected: steps 1–3 `SUCCEEDED`, step 4 `FAILED`, execution `COMPENSATED`. Timeline shows `cancel-shipment`, `refund-payment`, `release-inventory` all `SUCCEEDED`.

---

## Demo 3: Transient Failure + Retries

`reserve-inventory` fails twice then succeeds. Execution completes after 2 retries on that step.

```bash
EXEC=$(curl -sf -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-order-retry-$(date +%s)" \
  -d '{
    "definition_id": "c0000000-0000-0000-0000-000000000001",
    "input": {
      "order_id": "ORD-003",
      "customer_id": "CUST-001",
      "items": [{"sku": "WIDGET-C", "qty": 3}],
      "amount_cents": 7499,
      "currency": "EUR",
      "channel": "EMAIL",
      "shipping_address": {"street": "Römerberg 1", "city": "Frankfurt", "country": "DE"},
      "failure_config": {
        "fail_at_step": "reserve-inventory",
        "failure_type": "TRANSIENT",
        "fail_after_attempts": 2
      }
    }
  }')

export EXEC_ID=$(echo $EXEC | jq -r '.execution_id')

# Poll — will take longer due to retries with backoff
while true; do
  STATUS=$(curl -sf http://localhost:8082/api/v1/workflow-executions/$EXEC_ID \
    -H "Authorization: Bearer $TOKEN" | jq -r '.status')
  echo "Status: $STATUS"
  [[ "$STATUS" == "COMPLETED" || "$STATUS" == "COMPENSATED" || "$STATUS" == "FAILED" ]] && break
  sleep 3
done
```

Expected: execution `COMPLETED`. The timeline shows `reserve-inventory` with `attempt_count: 3`.

---

## Demo 4: Incident Escalation — Timeout Path

Wait-for-acknowledgment times out after 60 seconds. The `escalate-if-timeout` step runs automatically.

```bash
EXEC=$(curl -sf -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-incident-timeout-$(date +%s)" \
  -d '{
    "definition_id": "c0000000-0000-0000-0000-000000000002",
    "input": {
      "incident_id": "INC-001",
      "severity": "P1",
      "title": "Database connection pool exhausted",
      "service": "payments-service",
      "acknowledged": false
    }
  }')

export EXEC_ID=$(echo $EXEC | jq -r '.execution_id')
echo "Incident execution: $EXEC_ID"
echo "Waiting for EVENT_WAIT timeout (60s)..."

# Poll
while true; do
  STATUS=$(curl -sf http://localhost:8082/api/v1/workflow-executions/$EXEC_ID \
    -H "Authorization: Bearer $TOKEN" | jq -r '.status')
  echo "Status: $STATUS"
  [[ "$STATUS" == "COMPLETED" ]] && break
  sleep 5
done
```

Expected: after ~65 seconds, execution `COMPLETED` with `escalate-if-timeout` step `SUCCEEDED`.

---

## Demo 5: Incident Escalation — Acknowledged Path

Send the acknowledgment signal within 60 seconds.

```bash
EXEC=$(curl -sf -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-incident-ack-$(date +%s)" \
  -d '{
    "definition_id": "c0000000-0000-0000-0000-000000000002",
    "input": {
      "incident_id": "INC-002",
      "severity": "P2",
      "title": "High memory usage on worker-service pods",
      "service": "worker-service",
      "acknowledged": false
    }
  }')

export EXEC_ID=$(echo $EXEC | jq -r '.execution_id')

# Send signal (within 60s)
sleep 5
curl -X POST http://localhost:8082/api/v1/workflow-executions/$EXEC_ID/signal \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"event_name": "incident.acknowledged", "payload": {"acknowledged_by": "sre@acme.com"}}'
```

Expected: execution `COMPLETED` quickly after signal, `escalate-if-timeout` step is skipped.

---

## Demo 6: RBAC — Viewer Cannot Execute

```bash
VIEWER_TOKEN=$(curl -sf -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"viewer@acme.com","password":"Atlas2026!"}' \
  | jq -r '.access_token')

# Read — allowed (200)
curl -sf http://localhost:8082/api/v1/workflow-executions/$EXEC_ID \
  -H "Authorization: Bearer $VIEWER_TOKEN" | jq '.status'

# Execute — blocked (403 ATLAS-AUTH-004)
curl -v -X POST http://localhost:8082/api/v1/workflow-executions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $VIEWER_TOKEN" \
  -H "Idempotency-Key: viewer-blocked-$(date +%s)" \
  -d '{"definition_id":"c0000000-0000-0000-0000-000000000001","input":{}}' 2>&1 \
  | grep -E "HTTP|ATLAS"
```

Expected: read returns `200`, execute returns `403` with error code `ATLAS-AUTH-004`.

---

## Viewing the Audit Trail

```bash
# Query all events for the last execution
curl -sf "http://localhost:8084/api/v1/audit-events?entity_id=$EXEC_ID" \
  -H "Authorization: Bearer $TOKEN" | jq '.data[].event_type'
```

---

## Observability

Open Grafana at http://localhost:3000 (admin/admin). The Atlas dashboard shows:
- Execution throughput (started, completed, failed, compensated per minute)
- Step execution latency percentiles
- Worker lease acquisition rate
- Dead-letter queue depth
- Kafka consumer lag

Traces are visible in Grafana → Explore → Tempo. Search by `service.name=atlas-workflow-service` or paste a `trace_id` from an execution timeline response.
```

### Step 6.5 — `docs/local-setup.md`

```markdown
# Local Setup Guide

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Docker | 25+ | https://docs.docker.com/get-docker/ |
| Docker Compose | v2.24+ | Bundled with Docker Desktop |
| Java | 25 | https://adoptium.net or `sdk install java 25-tem` |
| Maven | 3.9+ | https://maven.apache.org/install.html |
| make | Any | Pre-installed on macOS/Linux |
| jq | 1.6+ | `brew install jq` / `apt install jq` |
| curl | Any | Pre-installed on macOS/Linux |

## First-Time Setup

```bash
# 1. Clone the repository
git clone https://github.com/OriolJT/atlas.git
cd atlas

# 2. Create local environment file
make setup-env
# This copies infra/.env.example to infra/.env
# Default values work for local development — no changes needed unless
# you have port conflicts.

# 3. Build all modules
make build
# This runs: mvn clean package -DskipTests

# 4. Build Docker images
make build-docker
# This builds: identity-service, workflow-service, worker-service, audit-service
# Takes ~3-5 minutes on first run (downloads Maven dependencies into Docker layers)
```

## Starting the Stack

```bash
# Option A — Start everything at once
make up
# Starts infrastructure + all application services

# Option B — Start infrastructure only (for local Java development)
make infra-up
# Then run individual services from your IDE or:
# mvn spring-boot:run -pl identity-service -Dspring-boot.run.profiles=local
```

## Seeding Demo Data

```bash
# Wait ~60s for all services to become healthy, then:
make seed

# Or check health first:
make health
```

## Stopping the Stack

```bash
make down           # Stop all containers (preserves volumes)
make infra-down     # Stop only infrastructure containers
docker compose -f infra/docker-compose.yml down -v   # Stop + delete volumes (full reset)
```

## Environment Variables

The `infra/.env` file controls all configuration. Key variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_USER` | `atlas` | PostgreSQL superuser |
| `POSTGRES_PASSWORD` | `atlas_secret` | PostgreSQL password |
| `POSTGRES_DB` | `atlas` | PostgreSQL database name |
| `ATLAS_JWT_SECRET` | `atlas-demo-secret-key-...` | JWT signing secret (min 32 chars) |
| `ATLAS_DB_URL` | `jdbc:postgresql://postgres:5432/atlas` | JDBC URL (used inside Docker network) |
| `ATLAS_KAFKA_BOOTSTRAP` | `kafka:9092` | Kafka bootstrap server (inside Docker network) |
| `ATLAS_REDIS_HOST` | `redis` | Redis hostname (inside Docker network) |
| `ATLAS_REDIS_PORT` | `6379` | Redis port |
| `ATLAS_IDENTITY_URL` | `http://identity-service:8081` | Identity Service URL (used by other services) |

## Running Tests

```bash
# All tests
make test

# Single module
mvn test -pl workflow-service

# Integration tests only
mvn test -pl identity-service -Dgroups=integration

# Skip integration tests (faster feedback)
mvn test -pl identity-service -DexcludedGroups=integration
```

Integration tests start their own containers via Testcontainers. You do not need the stack running.

## Port Reference

| Service | Host Port | Notes |
|---------|-----------|-------|
| identity-service | 8081 | Swagger: http://localhost:8081/swagger-ui.html |
| workflow-service | 8082 | Swagger: http://localhost:8082/swagger-ui.html |
| worker-service | 8083 | No public API (Kafka consumer + actuator only) |
| audit-service | 8084 | Swagger: http://localhost:8084/swagger-ui.html |
| PostgreSQL | 5432 | `psql -h localhost -U atlas -d atlas` |
| Kafka | 9092 | KRaft mode (no ZooKeeper) |
| Redis | 6379 | `redis-cli -h localhost ping` |
| Prometheus | 9090 | http://localhost:9090 |
| Grafana | 3000 | http://localhost:3000 (admin/admin) |
| Tempo | 3200 | http://localhost:3200 |

## Troubleshooting

**Services not starting / health check failing:**
```bash
# Check logs for a specific service
docker compose -f infra/docker-compose.yml --profile app logs identity-service -f
```

**Port already in use:**
Edit `infra/.env` or the `ports:` mapping in `infra/docker-compose.yml` to use a different host port.

**Seed fails with "Failed to obtain admin JWT":**
The identity-service Flyway migration may not have run yet. Wait 10–15 more seconds and retry: `make seed`.

**Maven build fails with "cannot find symbol" in common module:**
Run `mvn install -pl common -am` first to install the common module into local Maven cache.

**Full reset:**
```bash
make down
docker compose -f infra/docker-compose.yml down -v --remove-orphans
make up
make seed
```
```

**Commit message:** `docs: add architecture, API reference, tradeoffs, demo guide, and local setup documentation`

---

## Summary

| Task | Steps | Key Files |
|------|-------|-----------|
| 1 — Demo workflow definitions | 2 | `examples/workflows/order-fulfillment.json`, `examples/workflows/incident-escalation.json` |
| 2 — Seed script | 1 | `scripts/seed.sh` |
| 3 — Docker Compose app services | 3 | `identity-service/Dockerfile`, `workflow-service/Dockerfile`, `worker-service/Dockerfile`, `audit-service/Dockerfile`, `infra/.env.example`, `infra/docker-compose.yml` (updated) |
| 4 — Makefile | 1 | `Makefile` |
| 5 — Project README | 1 | `README.md` |
| 6 — Documentation files | 5 | `docs/architecture.md`, `docs/api.md`, `docs/tradeoffs.md`, `docs/demo-guide.md`, `docs/local-setup.md` |
| **Total** | **13 steps** | **16 files** |

## Commit Strategy

Each step maps to one commit. Suggested order:

1. `examples/workflows/` — 2 commits (one per definition)
2. `scripts/seed.sh` — 1 commit
3. Dockerfiles + `.env.example` + updated `docker-compose.yml` — 1 commit
4. `Makefile` — 1 commit
5. `README.md` — 1 commit
6. `docs/` files — 1 commit (all 5 docs together)

**Total: 7 commits** for Plan 5.
