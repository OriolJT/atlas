# Atlas API Reference

All endpoints are prefixed with `/api/v1/`. Every request and response carries an `X-Correlation-ID` header. All protected endpoints require a JWT bearer token in the `Authorization` header.

---

## Authentication

All endpoints except `/api/v1/auth/login`, `/api/v1/auth/refresh`, and `/actuator/*` require a valid JWT issued by the Identity Service. The token is passed as:

```
Authorization: Bearer <access_token>
```

JWT payload includes `sub` (user ID), `tenant_id`, `roles`, `iat`, and `exp`. Tokens expire after 15 minutes. Use the refresh endpoint to obtain a new access token without re-authenticating.

---

## Identity Service (port 8081)

### Auth Endpoints

| Method | Path | Auth | Permission | Description |
|--------|------|------|------------|-------------|
| POST | `/api/v1/auth/login` | None | — | Authenticate with tenantSlug + email + password, receive JWT + refresh token |
| POST | `/api/v1/auth/refresh` | None | — | Rotate refresh token, receive new access token |
| POST | `/api/v1/auth/logout` | Bearer | — | Revoke refresh token |

**POST /api/v1/auth/login**

Request:
```json
{
  "tenantSlug": "acme-corp",
  "email": "admin@acme.com",
  "password": "Atlas2026!"
}
```

Response `200 OK`:
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "refresh_token": "rt_abc123...",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

**POST /api/v1/auth/refresh**

Request:
```json
{
  "refresh_token": "rt_abc123..."
}
```

Response `200 OK`:
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiJ9...",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

**POST /api/v1/auth/logout**

Request:
```json
{
  "refresh_token": "rt_abc123..."
}
```

Response: `204 No Content`

---

### Tenant Endpoints

| Method | Path | Auth | Permission | Description |
|--------|------|------|------------|-------------|
| POST | `/api/v1/tenants` | Bearer | `tenant.manage` | Create a new tenant |
| GET | `/api/v1/tenants/{id}` | Bearer | `tenant.manage` | Get tenant details |

**POST /api/v1/tenants**

Request:
```json
{
  "name": "Acme Corp",
  "slug": "acme"
}
```

Response `201 Created`:
```json
{
  "tenant_id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Acme Corp",
  "slug": "acme",
  "status": "ACTIVE",
  "created_at": "2026-04-08T10:00:00Z"
}
```

---

### User Endpoints

| Method | Path | Auth | Permission | Description |
|--------|------|------|------------|-------------|
| POST | `/api/v1/users` | Bearer | `tenant.manage` | Create a user |
| GET | `/api/v1/users/{id}` | Bearer | `tenant.manage` | Get user details |

**POST /api/v1/users**

Request:
```json
{
  "email": "operator@acme.com",
  "password": "Atlas2026!",
  "display_name": "Acme Operator"
}
```

Response `201 Created`:
```json
{
  "user_id": "660e8400-e29b-41d4-a716-446655440001",
  "email": "operator@acme.com",
  "display_name": "Acme Operator",
  "tenant_id": "550e8400-e29b-41d4-a716-446655440000",
  "created_at": "2026-04-08T10:01:00Z"
}
```

---

### Role Endpoints

| Method | Path | Auth | Permission | Description |
|--------|------|------|------------|-------------|
| POST | `/api/v1/roles` | Bearer | `tenant.manage` | Create a role |
| POST | `/api/v1/roles/{id}/permissions` | Bearer | `tenant.manage` | Assign permissions to role |

---

### Service Account Endpoints

| Method | Path | Auth | Permission | Description |
|--------|------|------|------------|-------------|
| POST | `/api/v1/service-accounts` | Bearer | `tenant.manage` | Create a service account |
| POST | `/api/v1/api-keys` | Bearer | `tenant.manage` | Create an API key for a service account |

---

### Internal Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/internal/permissions` | Service-to-service | Fetch all role-permission mappings |
| GET | `/actuator/health` | None | Health check |
| GET | `/actuator/prometheus` | None | Prometheus metrics |

---

## Workflow Service (port 8082)

### Workflow Definition Endpoints

| Method | Path | Auth | Permission | Description |
|--------|------|------|------------|-------------|
| POST | `/api/v1/workflow-definitions` | Bearer | `workflow.manage` | Register a workflow definition (DRAFT) |
| GET | `/api/v1/workflow-definitions/{id}` | Bearer | `workflow.read` | Get definition details |
| POST | `/api/v1/workflow-definitions/{id}/publish` | Bearer | `workflow.manage` | Publish definition (enables execution) |

**POST /api/v1/workflow-definitions**

Request:
```json
{
  "name": "order-fulfillment",
  "version": 1,
  "trigger_type": "API",
  "steps": [
    {
      "name": "validate-order",
      "type": "INTERNAL_COMMAND",
      "retry_policy": {
        "max_attempts": 3,
        "backoff": "EXPONENTIAL",
        "initial_delay_ms": 1000
      },
      "timeout_ms": 30000,
      "compensation": null
    },
    {
      "name": "reserve-inventory",
      "type": "INTERNAL_COMMAND",
      "retry_policy": {
        "max_attempts": 3,
        "backoff": "FIXED",
        "initial_delay_ms": 2000
      },
      "timeout_ms": 60000,
      "compensation": "release-inventory"
    }
  ],
  "compensations": {
    "release-inventory": {
      "type": "INTERNAL_COMMAND",
      "config": {}
    }
  }
}
```

Response `201 Created`:
```json
{
  "definition_id": "def-abc123",
  "name": "order-fulfillment",
  "version": 1,
  "status": "DRAFT",
  "created_at": "2026-04-08T10:02:00Z"
}
```

**POST /api/v1/workflow-definitions/{id}/publish**

Response `200 OK`:
```json
{
  "definition_id": "def-abc123",
  "name": "order-fulfillment",
  "version": 1,
  "status": "PUBLISHED",
  "published_at": "2026-04-08T10:03:00Z"
}
```

---

### Workflow Execution Endpoints

| Method | Path | Auth | Permission | Description |
|--------|------|------|------------|-------------|
| POST | `/api/v1/workflow-executions` | Bearer | `workflow.execute` | Start execution (idempotency key required) |
| GET | `/api/v1/workflow-executions/{id}` | Bearer | `workflow.read` | Get execution status and step details |
| POST | `/api/v1/workflow-executions/{id}/cancel` | Bearer | `workflow.manage` | Cancel active execution |
| POST | `/api/v1/workflow-executions/{id}/signal` | Bearer | `workflow.execute` | Send signal to an EVENT_WAIT step |
| GET | `/api/v1/workflow-executions/{id}/timeline` | Bearer | `workflow.read` | Get ordered execution event timeline |

**POST /api/v1/workflow-executions**

Required header: `Idempotency-Key: <unique-key>`

Request:
```json
{
  "definition_id": "def-abc123",
  "input": {
    "order_id": "order-789",
    "failure_config": {
      "fail_at_step": "create-shipment",
      "failure_type": "TRANSIENT",
      "fail_after_attempts": 2
    }
  }
}
```

Response `202 Accepted`:
```json
{
  "execution_id": "exec-xyz789",
  "definition_id": "def-abc123",
  "status": "PENDING",
  "created_at": "2026-04-08T14:30:00Z"
}
```

**GET /api/v1/workflow-executions/{id}**

Response `200 OK`:
```json
{
  "execution_id": "exec-xyz789",
  "definition_id": "def-abc123",
  "status": "RUNNING",
  "current_step": "reserve-inventory",
  "created_at": "2026-04-08T14:30:00Z",
  "started_at": "2026-04-08T14:30:00Z",
  "steps": [
    {
      "step_name": "validate-order",
      "status": "SUCCEEDED",
      "attempt_count": 1,
      "started_at": "2026-04-08T14:30:01Z",
      "finished_at": "2026-04-08T14:30:02Z"
    },
    {
      "step_name": "reserve-inventory",
      "status": "RUNNING",
      "attempt_count": 1,
      "started_at": "2026-04-08T14:30:03Z",
      "finished_at": null
    }
  ]
}
```

**POST /api/v1/workflow-executions/{id}/signal**

Request:
```json
{
  "step_name": "wait-for-acknowledgment",
  "payload": {
    "acknowledged_by": "user-123"
  }
}
```

Response `200 OK`:
```json
{
  "execution_id": "exec-xyz789",
  "status": "RUNNING",
  "advanced_to": "escalate-if-timeout"
}
```

**GET /api/v1/workflow-executions/{id}/timeline**

Response `200 OK`:
```json
{
  "execution_id": "exec-xyz789",
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

---

### Dead-Letter Endpoints

| Method | Path | Auth | Permission | Description |
|--------|------|------|------------|-------------|
| GET | `/api/v1/dead-letter` | Bearer | `workflow.manage` | List dead-letter items |
| POST | `/api/v1/dead-letter/{id}/replay` | Bearer | `workflow.manage` | Replay a dead-letter item |

**GET /api/v1/dead-letter**

Query parameters: `execution_id`, `step_name`, `page`, `size`

Response `200 OK`:
```json
{
  "items": [
    {
      "dead_letter_id": "dl-001",
      "execution_id": "exec-xyz789",
      "step_name": "charge-payment",
      "attempt_count": 3,
      "last_error": "Connection refused",
      "created_at": "2026-04-08T14:31:00Z"
    }
  ],
  "total": 1,
  "page": 0,
  "size": 20
}
```

---

## Audit Service (port 8084)

### Audit Event Endpoints

| Method | Path | Auth | Permission | Description |
|--------|------|------|------------|-------------|
| GET | `/api/v1/audit-events` | Bearer | `audit.read` | Query audit events (filtered, paginated) |
| GET | `/actuator/health` | None | — | Health check |

**GET /api/v1/audit-events**

Query parameters:

| Parameter | Type | Description |
|-----------|------|-------------|
| `actor_id` | UUID | Filter by actor (user or service account) |
| `event_type` | string | Filter by event type (e.g., `workflow.execution.started`) |
| `resource_type` | string | Filter by resource type (e.g., `WORKFLOW_EXECUTION`) |
| `resource_id` | UUID | Filter by specific resource |
| `from` | ISO 8601 | Start of time range |
| `to` | ISO 8601 | End of time range |
| `page` | int | Page number (0-based) |
| `size` | int | Page size (default 20, max 100) |

Response `200 OK`:
```json
{
  "events": [
    {
      "audit_event_id": "ae-111",
      "tenant_id": "550e8400-e29b-41d4-a716-446655440000",
      "actor_type": "USER",
      "actor_id": "660e8400-e29b-41d4-a716-446655440001",
      "event_type": "workflow.execution.started",
      "resource_type": "WORKFLOW_EXECUTION",
      "resource_id": "exec-xyz789",
      "payload": { "definition_id": "def-abc123", "version": 1 },
      "correlation_id": "corr-abc",
      "occurred_at": "2026-04-08T14:30:00Z"
    }
  ],
  "next_cursor": "2026-04-08T14:30:00Z|ae-111",
  "has_more": false
}
```

---

## Common Headers

| Header | Direction | Description |
|--------|-----------|-------------|
| `Authorization` | Request | `Bearer <access_token>` |
| `X-Correlation-ID` | Request/Response | Propagated or generated per request; present in all logs and traces |
| `Idempotency-Key` | Request | Required for `POST /api/v1/workflow-executions` |

---

## Error Codes

All error responses use the following structure:

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

### Error Code Taxonomy

| Code | Service | HTTP Status | Meaning |
|------|---------|-------------|---------|
| `ATLAS-AUTH-001` | Identity | 401 | Invalid credentials |
| `ATLAS-AUTH-002` | Identity | 403 | Account locked |
| `ATLAS-AUTH-003` | Identity | 401 | Token expired |
| `ATLAS-AUTH-004` | Identity | 403 | Insufficient permissions |
| `ATLAS-AUTH-005` | Identity | 401 | Refresh token revoked |
| `ATLAS-TENANT-001` | Identity | 404 | Tenant not found |
| `ATLAS-TENANT-002` | Identity | 403 | Tenant suspended |
| `ATLAS-WF-001` | Workflow | 404 | Definition not found |
| `ATLAS-WF-002` | Workflow | 409 | Definition not published |
| `ATLAS-WF-003` | Workflow | 404 | Execution not found |
| `ATLAS-WF-004` | Workflow | 409 | Invalid state transition |
| `ATLAS-WF-005` | Workflow | 409 | Idempotency key conflict |
| `ATLAS-WF-006` | Workflow | 409 | Signal rejected (step not waiting) |
| `ATLAS-WF-007` | Workflow | 409 | Cancellation rejected (invalid state) |
| `ATLAS-DL-001` | Workflow | 404 | Dead-letter item not found |
| `ATLAS-AUDIT-001` | Audit | 400 | Invalid query parameters |
| `ATLAS-COMMON-001` | Any | 400 | Validation failed (see `errors` array) |
| `ATLAS-COMMON-002` | Any | 403 | Tenant scope mismatch |

---

## Actuator Endpoints

All services expose the following unauthenticated endpoints:

| Path | Description |
|------|-------------|
| `/actuator/health` | Health check with DB, Kafka, and Redis indicators. Returns `UP`/`DOWN`. |
| `/actuator/prometheus` | Prometheus metrics scrape endpoint |
| `/actuator/info` | Service name, version, and git commit |

In dev/local profiles, each service also serves:

| Path | Description |
|------|-------------|
| `/swagger-ui.html` | Interactive OpenAPI explorer |
| `/v3/api-docs` | Machine-readable OpenAPI 3.0 JSON |

---

## RBAC Reference

| Role | Permissions |
|------|-------------|
| `TENANT_ADMIN` | All permissions |
| `WORKFLOW_OPERATOR` | `workflow.read`, `workflow.execute`, `workflow.manage`, `audit.read` |
| `VIEWER` | `workflow.read`, `audit.read` |

Default roles are seeded per tenant on creation.
