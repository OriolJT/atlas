// --- Auth --------------------------------------------------------------------

export interface LoginRequest {
  tenantSlug: string
  email: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  refreshToken: string
  userId: string
  tenantId: string
}

export interface RefreshTokenRequest {
  refreshToken: string
}

// --- Workflow Definitions ----------------------------------------------------

export type DefinitionStatus = 'DRAFT' | 'PUBLISHED' | 'DEPRECATED'

export interface StepDefinition {
  key: string
  name: string
  type: 'INTERNAL_COMMAND' | 'HTTP' | 'DELAY' | 'EVENT_WAIT'
  max_attempts: number
  compensation_step_key?: string
  [key: string]: unknown
}

export interface WorkflowDefinition {
  id: string
  name: string
  version: number
  status: DefinitionStatus
  description?: string
  steps: StepDefinition[]
  created_at: string
  updated_at: string
}

export interface WorkflowDefinitionListResponse {
  items: WorkflowDefinition[]
  next_cursor?: string
}

export interface CreateDefinitionRequest {
  name: string
  description?: string
  steps: StepDefinition[]
}

// --- Workflow Executions -----------------------------------------------------

export type ExecutionStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'WAITING'
  | 'COMPENSATING'
  | 'COMPLETED'
  | 'COMPENSATED'
  | 'FAILED'
  | 'CANCELED'

export interface WorkflowExecution {
  id: string
  workflow_definition_id: string
  workflow_name: string
  version: number
  status: ExecutionStatus
  input: Record<string, unknown>
  output?: Record<string, unknown>
  started_at: string
  finished_at?: string
  correlation_id?: string
}

export interface WorkflowExecutionListResponse {
  items: WorkflowExecution[]
  next_cursor?: string
}

export interface StartExecutionRequest {
  workflow_definition_id: string
  input: Record<string, unknown>
  idempotency_key: string
}

// --- Execution Timeline ------------------------------------------------------

export type StepStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'RETRY_SCHEDULED'
  | 'DEAD_LETTERED'
  | 'SKIPPED'
  | 'COMPENSATING'
  | 'COMPENSATED'

export interface TimelineEvent {
  step_key: string
  step_name: string
  status: StepStatus
  attempt_count: number
  started_at?: string
  finished_at?: string
  duration_ms?: number
  error_code?: string
  error_message?: string
  is_compensation: boolean
}

export interface ExecutionTimeline {
  execution_id: string
  events: TimelineEvent[]
}

// --- Dead Letter -------------------------------------------------------------

export interface DeadLetterItem {
  id: string
  execution_id: string
  step_key: string
  step_name: string
  attempt_count: number
  error_code?: string
  error_message?: string
  payload: Record<string, unknown>
  created_at: string
}

export interface DeadLetterListResponse {
  items: DeadLetterItem[]
  next_cursor?: string
}

// --- Audit -------------------------------------------------------------------

export interface AuditEvent {
  id: string
  event_type: string
  resource_type: string
  resource_id: string
  actor_id: string
  actor_email?: string
  payload: Record<string, unknown>
  created_at: string
}

export interface AuditEventListResponse {
  items: AuditEvent[]
  next_cursor?: string
}

// --- Dashboard ---------------------------------------------------------------

export interface DashboardStats {
  active_executions: number
  failed_executions: number
  dead_letter_count: number
  recent_executions: WorkflowExecution[]
}

// --- API Error ---------------------------------------------------------------

export interface ApiError {
  code: string
  message: string
  details?: string
  errors?: Array<{ field: string; message: string }>
  correlation_id?: string
  timestamp: string
}
