# Plan 8: Admin Console UI (React)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan.

**Goal:** Build a lightweight React admin console that provides a visual interface for Atlas's API — login, workflow management, execution monitoring with timeline visualization, dead-letter management, and audit trail browsing.

**Architecture:** Vite + React + TypeScript SPA in admin-console/ directory. Communicates with Atlas services via REST APIs. Served by a simple nginx container in Docker Compose. No SSR, no state management library — just React hooks + fetch.

**Tech Stack:** React 19, TypeScript, Vite, Tailwind CSS, React Router

**Depends on:** Plans 1-6 (all API endpoints)

**Produces:** A working admin UI accessible at http://localhost:5173 (dev) or http://localhost:3001 (Docker)

---

## Service Port Reference

| Service | Port | Used for |
|---------|------|----------|
| identity-service | 8081 | Auth (login, refresh, logout) |
| workflow-service | 8082 | Definitions, executions, dead-letter |
| audit-service | 8084 | Audit events |

---

## File Structure

```
admin-console/
  index.html
  vite.config.ts
  tailwind.config.ts
  tsconfig.json
  package.json
  nginx.conf
  Dockerfile
  src/
    main.tsx
    App.tsx
    api.ts
    types.ts
    contexts/
      AuthContext.tsx
    components/
      Layout.tsx
      Sidebar.tsx
      LoadingSpinner.tsx
      ErrorMessage.tsx
      Badge.tsx
    pages/
      LoginPage.tsx
      DashboardPage.tsx
      WorkflowDefinitionsPage.tsx
      WorkflowExecutionsPage.tsx
      ExecutionDetailPage.tsx
      DeadLetterPage.tsx
      AuditTrailPage.tsx
```

Total: **8 tasks**, **28 files**.

---

## Critical Rules

- TypeScript throughout — no `any` types; define all shapes in `src/types.ts`
- Tailwind CSS for all styling — no custom CSS files
- No state management library — React Context + hooks only
- Fetch API for HTTP — no axios
- All API calls go through `src/api.ts` which injects the JWT `Authorization` header
- Error handling on every API call (display `ErrorMessage` component, never swallow)
- Loading state on every data fetch (display `LoadingSpinner`, disable submit buttons)
- Responsive layout that works at 1280px+ desktop width
- Clean, professional UI — neutral grays, status colors (green/yellow/red/blue)

---

## Task 1: Project Scaffolding

### Step 1.1 — Bootstrap with Vite

Run from the project root (`/path/to/atlas/`):

```bash
npm create vite@latest admin-console -- --template react-ts
cd admin-console
npm install
npm install -D tailwindcss @tailwindcss/vite
npm install react-router-dom
npm install -D @types/react-router-dom
```

### Step 1.2 — `vite.config.ts`

**File:** `admin-console/vite.config.ts`

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api/v1/auth': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/v1/tenants': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/v1/users': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/v1/workflow-definitions': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api/v1/workflow-executions': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api/v1/dead-letter': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api/v1/audit-events': {
        target: 'http://localhost:8084',
        changeOrigin: true,
      },
    },
  },
})
```

### Step 1.3 — `tailwind.config.ts`

**File:** `admin-console/tailwind.config.ts`

```typescript
import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {},
  },
  plugins: [],
} satisfies Config
```

### Step 1.4 — `src/index.css`

Replace the generated `index.css` with:

**File:** `admin-console/src/index.css`

```css
@import "tailwindcss";
```

### Step 1.5 — `src/types.ts`

Define all API response shapes here. Every component imports from this file — never use inline type literals for API data.

**File:** `admin-console/src/types.ts`

```typescript
// ─── Auth ────────────────────────────────────────────────────────────────────

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  access_token: string
  refresh_token: string
  expires_in: number
}

// ─── Workflow Definitions ─────────────────────────────────────────────────────

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

// ─── Workflow Executions ──────────────────────────────────────────────────────

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

// ─── Execution Timeline ───────────────────────────────────────────────────────

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

// ─── Dead Letter ──────────────────────────────────────────────────────────────

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

// ─── Audit ────────────────────────────────────────────────────────────────────

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

// ─── Dashboard ────────────────────────────────────────────────────────────────

export interface DashboardStats {
  active_executions: number
  failed_executions: number
  dead_letter_count: number
  recent_executions: WorkflowExecution[]
}

// ─── API Error ────────────────────────────────────────────────────────────────

export interface ApiError {
  code: string
  message: string
  details?: string
  errors?: Array<{ field: string; message: string }>
  correlation_id?: string
  timestamp: string
}
```

### Step 1.6 — `src/api.ts`

Central fetch utility. All components call these functions — never call `fetch()` directly.

**File:** `admin-console/src/api.ts`

```typescript
import type { ApiError } from './types'

// The JWT is stored in memory only (not localStorage) via AuthContext.
// api.ts receives the token as a parameter — components get it from useAuth().
// This keeps api.ts a pure utility with no side effects.

export class AtlasApiError extends Error {
  public readonly status: number
  public readonly body: ApiError

  constructor(status: number, body: ApiError) {
    super(body.message)
    this.name = 'AtlasApiError'
    this.status = status
    this.body = body
  }
}

async function request<T>(
  path: string,
  options: RequestInit,
  token?: string,
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined),
  }

  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }

  const response = await fetch(path, { ...options, headers })

  if (!response.ok) {
    let body: ApiError
    try {
      body = (await response.json()) as ApiError
    } catch {
      body = {
        code: 'ATLAS-COMMON-000',
        message: `HTTP ${response.status}: ${response.statusText}`,
        timestamp: new Date().toISOString(),
      }
    }
    throw new AtlasApiError(response.status, body)
  }

  // 204 No Content
  if (response.status === 204) {
    return undefined as unknown as T
  }

  return response.json() as Promise<T>
}

export function get<T>(path: string, token?: string): Promise<T> {
  return request<T>(path, { method: 'GET' }, token)
}

export function post<T>(path: string, body: unknown, token?: string): Promise<T> {
  return request<T>(path, { method: 'POST', body: JSON.stringify(body) }, token)
}

export function postEmpty<T>(path: string, token?: string): Promise<T> {
  return request<T>(path, { method: 'POST', body: '{}' }, token)
}
```

### Step 1.7 — `src/contexts/AuthContext.tsx`

**File:** `admin-console/src/contexts/AuthContext.tsx`

```typescript
import { createContext, useContext, useState, useCallback, type ReactNode } from 'react'
import { post } from '../api'
import type { LoginRequest, LoginResponse } from '../types'

interface AuthState {
  token: string | null
  isAuthenticated: boolean
}

interface AuthContextValue extends AuthState {
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(null)

  const login = useCallback(async (email: string, password: string) => {
    const response = await post<LoginResponse>('/api/v1/auth/login', {
      email,
      password,
    } satisfies LoginRequest)
    setToken(response.access_token)
  }, [])

  const logout = useCallback(() => {
    setToken(null)
  }, [])

  return (
    <AuthContext.Provider
      value={{ token, isAuthenticated: token !== null, login, logout }}
    >
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
```

### Step 1.8 — Shared UI components

**File:** `admin-console/src/components/LoadingSpinner.tsx`

```typescript
export function LoadingSpinner() {
  return (
    <div className="flex items-center justify-center py-12">
      <div className="h-8 w-8 animate-spin rounded-full border-4 border-gray-200 border-t-blue-600" />
    </div>
  )
}
```

**File:** `admin-console/src/components/ErrorMessage.tsx`

```typescript
import type { AtlasApiError } from '../api'

interface Props {
  error: AtlasApiError | Error | null
}

export function ErrorMessage({ error }: Props) {
  if (!error) return null

  const isAtlasError = error instanceof Error && 'body' in error
  const message = isAtlasError
    ? (error as AtlasApiError).body.message
    : error.message

  return (
    <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
      <span className="font-medium">Error:</span> {message}
    </div>
  )
}
```

**File:** `admin-console/src/components/Badge.tsx`

```typescript
type Variant = 'green' | 'yellow' | 'red' | 'blue' | 'gray'

interface Props {
  label: string
  variant: Variant
}

const variantClasses: Record<Variant, string> = {
  green: 'bg-green-100 text-green-800',
  yellow: 'bg-yellow-100 text-yellow-800',
  red: 'bg-red-100 text-red-800',
  blue: 'bg-blue-100 text-blue-800',
  gray: 'bg-gray-100 text-gray-700',
}

export function Badge({ label, variant }: Props) {
  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${variantClasses[variant]}`}
    >
      {label}
    </span>
  )
}
```

### Step 1.9 — Layout components

**File:** `admin-console/src/components/Sidebar.tsx`

```typescript
import { NavLink } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'

const navItems = [
  { to: '/', label: 'Dashboard', exact: true },
  { to: '/definitions', label: 'Workflow Definitions', exact: false },
  { to: '/executions', label: 'Executions', exact: false },
  { to: '/dead-letter', label: 'Dead Letter', exact: false },
  { to: '/audit', label: 'Audit Trail', exact: false },
]

export function Sidebar() {
  const { logout } = useAuth()

  return (
    <aside className="flex h-screen w-56 flex-col bg-gray-900 text-white">
      <div className="px-6 py-5 text-lg font-semibold tracking-tight text-white">
        Atlas
      </div>
      <nav className="flex-1 space-y-1 px-3">
        {navItems.map(({ to, label, exact }) => (
          <NavLink
            key={to}
            to={to}
            end={exact}
            className={({ isActive }) =>
              `block rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-gray-700 text-white'
                  : 'text-gray-400 hover:bg-gray-800 hover:text-white'
              }`
            }
          >
            {label}
          </NavLink>
        ))}
      </nav>
      <div className="px-3 py-4">
        <button
          onClick={logout}
          className="w-full rounded-md px-3 py-2 text-left text-sm font-medium text-gray-400 hover:bg-gray-800 hover:text-white transition-colors"
        >
          Sign out
        </button>
      </div>
    </aside>
  )
}
```

**File:** `admin-console/src/components/Layout.tsx`

```typescript
import type { ReactNode } from 'react'
import { Sidebar } from './Sidebar'

interface Props {
  children: ReactNode
}

export function Layout({ children }: Props) {
  return (
    <div className="flex min-h-screen bg-gray-50">
      <Sidebar />
      <main className="flex-1 overflow-y-auto p-8">{children}</main>
    </div>
  )
}
```

### Step 1.10 — `src/App.tsx` and `src/main.tsx`

**File:** `admin-console/src/App.tsx`

```typescript
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './contexts/AuthContext'
import { Layout } from './components/Layout'
import { LoginPage } from './pages/LoginPage'
import { DashboardPage } from './pages/DashboardPage'
import { WorkflowDefinitionsPage } from './pages/WorkflowDefinitionsPage'
import { WorkflowExecutionsPage } from './pages/WorkflowExecutionsPage'
import { ExecutionDetailPage } from './pages/ExecutionDetailPage'
import { DeadLetterPage } from './pages/DeadLetterPage'
import { AuditTrailPage } from './pages/AuditTrailPage'

function PrivateRoutes() {
  const { isAuthenticated } = useAuth()
  if (!isAuthenticated) return <Navigate to="/login" replace />

  return (
    <Layout>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/definitions" element={<WorkflowDefinitionsPage />} />
        <Route path="/executions" element={<WorkflowExecutionsPage />} />
        <Route path="/executions/:id" element={<ExecutionDetailPage />} />
        <Route path="/dead-letter" element={<DeadLetterPage />} />
        <Route path="/audit" element={<AuditTrailPage />} />
      </Routes>
    </Layout>
  )
}

export function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/*" element={<PrivateRoutes />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
```

**File:** `admin-console/src/main.tsx`

```typescript
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import { App } from './App'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

---

## Task 2: Login Page

**File:** `admin-console/src/pages/LoginPage.tsx`

**What it does:**
- Renders an email + password form centered on screen
- On submit: calls `POST /api/v1/auth/login` via `useAuth().login()`
- On success: redirects to `/` (dashboard) via `useNavigate`
- On error: shows `ErrorMessage` component with the API error
- Submit button shows "Signing in…" and is disabled during the fetch

```typescript
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { ErrorMessage } from '../components/ErrorMessage'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<Error | null>(null)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError(null)
    try {
      await login(email, password)
      navigate('/')
    } catch (err) {
      setError(err instanceof Error ? err : new Error('Unknown error'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50">
      <div className="w-full max-w-sm rounded-xl border border-gray-200 bg-white p-8 shadow-sm">
        <h1 className="mb-6 text-center text-2xl font-semibold text-gray-900">
          Atlas Admin
        </h1>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label
              htmlFor="email"
              className="block text-sm font-medium text-gray-700"
            >
              Email
            </label>
            <input
              id="email"
              type="email"
              required
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>
          <div>
            <label
              htmlFor="password"
              className="block text-sm font-medium text-gray-700"
            >
              Password
            </label>
            <input
              id="password"
              type="password"
              required
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
            />
          </div>
          {error && <ErrorMessage error={error} />}
          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {loading ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
        <p className="mt-4 text-center text-xs text-gray-400">
          Demo credentials: admin@acme.com / Atlas2026!
        </p>
      </div>
    </div>
  )
}
```

---

## Task 3: Dashboard

**File:** `admin-console/src/pages/DashboardPage.tsx`

**What it does:**
- Fetches counts by calling `GET /api/v1/workflow-executions?status=RUNNING&limit=1` (for active count), `?status=FAILED`, and `GET /api/v1/dead-letter` concurrently with `Promise.all`
- Fetches last 10 executions via `GET /api/v1/workflow-executions?limit=10`
- Displays 3 stat cards: Active Executions, Failed Executions, Dead Letter Items
- Displays a recent executions table (id truncated, name, status badge, started_at)
- Each execution row links to `/executions/:id`
- Shows `LoadingSpinner` while fetching; shows `ErrorMessage` on failure

```typescript
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { get } from '../api'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { ErrorMessage } from '../components/ErrorMessage'
import { Badge } from '../components/Badge'
import type {
  WorkflowExecutionListResponse,
  DeadLetterListResponse,
  WorkflowExecution,
  ExecutionStatus,
} from '../types'

function statusVariant(status: ExecutionStatus) {
  switch (status) {
    case 'COMPLETED':
    case 'COMPENSATED':
      return 'green'
    case 'RUNNING':
    case 'WAITING':
    case 'COMPENSATING':
      return 'blue'
    case 'FAILED':
    case 'CANCELED':
      return 'red'
    default:
      return 'gray'
  }
}

interface Stats {
  active: number
  failed: number
  deadLetter: number
  recent: WorkflowExecution[]
}

export function DashboardPage() {
  const { token } = useAuth()
  const [stats, setStats] = useState<Stats | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)

  useEffect(() => {
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const [activeRes, failedRes, dlRes, recentRes] = await Promise.all([
          get<WorkflowExecutionListResponse>(
            '/api/v1/workflow-executions?status=RUNNING&limit=1',
            token ?? undefined,
          ),
          get<WorkflowExecutionListResponse>(
            '/api/v1/workflow-executions?status=FAILED&limit=1',
            token ?? undefined,
          ),
          get<DeadLetterListResponse>('/api/v1/dead-letter?limit=1', token ?? undefined),
          get<WorkflowExecutionListResponse>(
            '/api/v1/workflow-executions?limit=10',
            token ?? undefined,
          ),
        ])
        setStats({
          active: activeRes.items.length > 0 ? 1 : 0, // approximate — replace with count endpoint if available
          failed: failedRes.items.length > 0 ? 1 : 0,
          deadLetter: dlRes.items.length > 0 ? 1 : 0,
          recent: recentRes.items,
        })
      } catch (err) {
        setError(err instanceof Error ? err : new Error('Failed to load dashboard'))
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [token])

  if (loading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={error} />
  if (!stats) return null

  const statCards = [
    { label: 'Active Executions', value: stats.active, color: 'text-blue-600' },
    { label: 'Failed Executions', value: stats.failed, color: 'text-red-600' },
    { label: 'Dead Letter Items', value: stats.deadLetter, color: 'text-yellow-600' },
  ]

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-semibold text-gray-900">Dashboard</h1>

      <div className="grid grid-cols-3 gap-6">
        {statCards.map(({ label, value, color }) => (
          <div
            key={label}
            className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm"
          >
            <p className="text-sm font-medium text-gray-500">{label}</p>
            <p className={`mt-2 text-3xl font-bold ${color}`}>{value}</p>
          </div>
        ))}
      </div>

      <div>
        <h2 className="mb-4 text-lg font-semibold text-gray-800">Recent Executions</h2>
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-3">ID</th>
                <th className="px-4 py-3">Workflow</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Started</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {stats.recent.map((exec) => (
                <tr key={exec.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-mono text-xs">
                    <Link
                      to={`/executions/${exec.id}`}
                      className="text-blue-600 hover:underline"
                    >
                      {exec.id.slice(0, 8)}…
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-gray-900">{exec.workflow_name}</td>
                  <td className="px-4 py-3">
                    <Badge label={exec.status} variant={statusVariant(exec.status)} />
                  </td>
                  <td className="px-4 py-3 text-gray-500">
                    {new Date(exec.started_at).toLocaleString()}
                  </td>
                </tr>
              ))}
              {stats.recent.length === 0 && (
                <tr>
                  <td colSpan={4} className="px-4 py-8 text-center text-gray-400">
                    No executions yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      <div className="flex gap-4">
        <Link
          to="/executions"
          className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
        >
          View all executions
        </Link>
        <Link
          to="/dead-letter"
          className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors"
        >
          Dead letter queue
        </Link>
      </div>
    </div>
  )
}
```

---

## Task 4: Workflow Definitions Page

**File:** `admin-console/src/pages/WorkflowDefinitionsPage.tsx`

**What it does:**
- Fetches `GET /api/v1/workflow-definitions` and renders a table: name, version, status badge, created_at
- "New Definition" button opens an inline form (slides down in-page, no modal library) with:
  - Name field (text input)
  - Description field (optional textarea)
  - Steps JSON textarea (pre-filled with an example 2-step JSON)
  - Submit: `POST /api/v1/workflow-definitions`
- Each row has a "Publish" button (if status is `DRAFT`) that calls `POST /api/v1/workflow-definitions/{id}/publish`
- Each row has a "View" link that opens a detail panel (inline, below table) showing the full definition JSON
- Full error/loading handling

```typescript
import { useEffect, useState } from 'react'
import { useAuth } from '../contexts/AuthContext'
import { get, post, postEmpty } from '../api'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { ErrorMessage } from '../components/ErrorMessage'
import { Badge } from '../components/Badge'
import type {
  WorkflowDefinition,
  WorkflowDefinitionListResponse,
  DefinitionStatus,
  CreateDefinitionRequest,
  StepDefinition,
} from '../types'

function statusVariant(status: DefinitionStatus) {
  switch (status) {
    case 'PUBLISHED': return 'green'
    case 'DRAFT': return 'yellow'
    case 'DEPRECATED': return 'gray'
  }
}

const EXAMPLE_STEPS: StepDefinition[] = [
  {
    key: 'step-one',
    name: 'Step One',
    type: 'INTERNAL_COMMAND',
    max_attempts: 3,
  },
  {
    key: 'step-two',
    name: 'Step Two',
    type: 'INTERNAL_COMMAND',
    max_attempts: 3,
    compensation_step_key: 'compensate-step-two',
  },
]

export function WorkflowDefinitionsPage() {
  const { token } = useAuth()
  const [definitions, setDefinitions] = useState<WorkflowDefinition[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [showForm, setShowForm] = useState(false)
  const [selectedDef, setSelectedDef] = useState<WorkflowDefinition | null>(null)

  // Form state
  const [formName, setFormName] = useState('')
  const [formDescription, setFormDescription] = useState('')
  const [formStepsJson, setFormStepsJson] = useState(JSON.stringify(EXAMPLE_STEPS, null, 2))
  const [formLoading, setFormLoading] = useState(false)
  const [formError, setFormError] = useState<Error | null>(null)

  async function fetchDefinitions() {
    setLoading(true)
    setError(null)
    try {
      const res = await get<WorkflowDefinitionListResponse>(
        '/api/v1/workflow-definitions',
        token ?? undefined,
      )
      setDefinitions(res.items)
    } catch (err) {
      setError(err instanceof Error ? err : new Error('Failed to load definitions'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchDefinitions() }, [token])

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    setFormLoading(true)
    setFormError(null)
    try {
      let steps: StepDefinition[]
      try {
        steps = JSON.parse(formStepsJson) as StepDefinition[]
      } catch {
        throw new Error('Steps JSON is invalid')
      }
      const body: CreateDefinitionRequest = {
        name: formName,
        description: formDescription || undefined,
        steps,
      }
      await post<WorkflowDefinition>('/api/v1/workflow-definitions', body, token ?? undefined)
      setShowForm(false)
      setFormName('')
      setFormDescription('')
      setFormStepsJson(JSON.stringify(EXAMPLE_STEPS, null, 2))
      await fetchDefinitions()
    } catch (err) {
      setFormError(err instanceof Error ? err : new Error('Failed to create definition'))
    } finally {
      setFormLoading(false)
    }
  }

  async function handlePublish(id: string) {
    try {
      await postEmpty(`/api/v1/workflow-definitions/${id}/publish`, token ?? undefined)
      await fetchDefinitions()
    } catch (err) {
      setError(err instanceof Error ? err : new Error('Failed to publish definition'))
    }
  }

  if (loading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-gray-900">Workflow Definitions</h1>
        <button
          onClick={() => { setShowForm(!showForm); setFormError(null) }}
          className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
        >
          {showForm ? 'Cancel' : 'New Definition'}
        </button>
      </div>

      {error && <ErrorMessage error={error} />}

      {showForm && (
        <div className="rounded-xl border border-blue-200 bg-blue-50 p-6">
          <h2 className="mb-4 text-base font-semibold text-gray-800">Create Definition</h2>
          <form onSubmit={handleCreate} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700">Name</label>
              <input
                type="text"
                required
                value={formName}
                onChange={(e) => setFormName(e.target.value)}
                className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
                placeholder="order-fulfillment"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700">Description (optional)</label>
              <textarea
                value={formDescription}
                onChange={(e) => setFormDescription(e.target.value)}
                rows={2}
                className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700">Steps (JSON array)</label>
              <textarea
                value={formStepsJson}
                onChange={(e) => setFormStepsJson(e.target.value)}
                rows={10}
                className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            {formError && <ErrorMessage error={formError} />}
            <button
              type="submit"
              disabled={formLoading}
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {formLoading ? 'Creating…' : 'Create Definition'}
            </button>
          </form>
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">Name</th>
              <th className="px-4 py-3">Version</th>
              <th className="px-4 py-3">Status</th>
              <th className="px-4 py-3">Created</th>
              <th className="px-4 py-3">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {definitions.map((def) => (
              <>
                <tr key={def.id} className="hover:bg-gray-50">
                  <td className="px-4 py-3 font-medium text-gray-900">{def.name}</td>
                  <td className="px-4 py-3 text-gray-500">v{def.version}</td>
                  <td className="px-4 py-3">
                    <Badge label={def.status} variant={statusVariant(def.status)} />
                  </td>
                  <td className="px-4 py-3 text-gray-500">
                    {new Date(def.created_at).toLocaleDateString()}
                  </td>
                  <td className="flex gap-2 px-4 py-3">
                    {def.status === 'DRAFT' && (
                      <button
                        onClick={() => handlePublish(def.id)}
                        className="rounded bg-green-100 px-2 py-1 text-xs font-medium text-green-800 hover:bg-green-200 transition-colors"
                      >
                        Publish
                      </button>
                    )}
                    <button
                      onClick={() =>
                        setSelectedDef(selectedDef?.id === def.id ? null : def)
                      }
                      className="rounded bg-gray-100 px-2 py-1 text-xs font-medium text-gray-700 hover:bg-gray-200 transition-colors"
                    >
                      {selectedDef?.id === def.id ? 'Hide' : 'View'}
                    </button>
                  </td>
                </tr>
                {selectedDef?.id === def.id && (
                  <tr key={`${def.id}-detail`}>
                    <td colSpan={5} className="bg-gray-50 px-4 py-4">
                      <pre className="overflow-x-auto rounded border border-gray-200 bg-white p-4 text-xs text-gray-700">
                        {JSON.stringify(def, null, 2)}
                      </pre>
                    </td>
                  </tr>
                )}
              </>
            ))}
            {definitions.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center text-gray-400">
                  No definitions yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
```

---

## Task 5: Workflow Executions Page

**File:** `admin-console/src/pages/WorkflowExecutionsPage.tsx`

**What it does:**
- Fetches `GET /api/v1/workflow-executions` and renders a table: id, workflow name, status, started_at, duration
- Status filter buttons (All / Running / Completed / Failed) append `?status=...` to the fetch URL
- "Start Execution" button opens inline form with: definition selector (dropdown populated from `GET /api/v1/workflow-definitions?status=PUBLISHED`) and input JSON textarea
  - Submit: `POST /api/v1/workflow-executions` with `idempotency_key` auto-generated as `crypto.randomUUID()`
- Clicking a row navigates to `/executions/:id`

```typescript
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { get, post } from '../api'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { ErrorMessage } from '../components/ErrorMessage'
import { Badge } from '../components/Badge'
import type {
  WorkflowExecution,
  WorkflowExecutionListResponse,
  WorkflowDefinitionListResponse,
  WorkflowDefinition,
  ExecutionStatus,
  StartExecutionRequest,
} from '../types'

type StatusFilter = 'ALL' | ExecutionStatus

function statusVariant(status: ExecutionStatus) {
  switch (status) {
    case 'COMPLETED':
    case 'COMPENSATED':
      return 'green'
    case 'RUNNING':
    case 'WAITING':
    case 'COMPENSATING':
      return 'blue'
    case 'FAILED':
    case 'CANCELED':
      return 'red'
    default:
      return 'gray'
  }
}

function durationLabel(exec: WorkflowExecution): string {
  if (!exec.finished_at) return '—'
  const ms = new Date(exec.finished_at).getTime() - new Date(exec.started_at).getTime()
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

const STATUS_FILTERS: StatusFilter[] = ['ALL', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELED']

export function WorkflowExecutionsPage() {
  const { token } = useAuth()
  const navigate = useNavigate()
  const [executions, setExecutions] = useState<WorkflowExecution[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')
  const [showForm, setShowForm] = useState(false)
  const [definitions, setDefinitions] = useState<WorkflowDefinition[]>([])
  const [selectedDefId, setSelectedDefId] = useState('')
  const [inputJson, setInputJson] = useState('{}')
  const [formLoading, setFormLoading] = useState(false)
  const [formError, setFormError] = useState<Error | null>(null)

  async function fetchExecutions(filter: StatusFilter) {
    setLoading(true)
    setError(null)
    try {
      const query = filter !== 'ALL' ? `?status=${filter}` : ''
      const res = await get<WorkflowExecutionListResponse>(
        `/api/v1/workflow-executions${query}`,
        token ?? undefined,
      )
      setExecutions(res.items)
    } catch (err) {
      setError(err instanceof Error ? err : new Error('Failed to load executions'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchExecutions(statusFilter) }, [statusFilter, token])

  async function openForm() {
    setShowForm(true)
    setFormError(null)
    try {
      const res = await get<WorkflowDefinitionListResponse>(
        '/api/v1/workflow-definitions?status=PUBLISHED',
        token ?? undefined,
      )
      setDefinitions(res.items)
      if (res.items.length > 0) setSelectedDefId(res.items[0].id)
    } catch (err) {
      setFormError(err instanceof Error ? err : new Error('Failed to load definitions'))
    }
  }

  async function handleStart(e: React.FormEvent) {
    e.preventDefault()
    setFormLoading(true)
    setFormError(null)
    try {
      let input: Record<string, unknown>
      try {
        input = JSON.parse(inputJson) as Record<string, unknown>
      } catch {
        throw new Error('Input JSON is invalid')
      }
      const body: StartExecutionRequest = {
        workflow_definition_id: selectedDefId,
        input,
        idempotency_key: crypto.randomUUID(),
      }
      const exec = await post<WorkflowExecution>('/api/v1/workflow-executions', body, token ?? undefined)
      setShowForm(false)
      navigate(`/executions/${exec.id}`)
    } catch (err) {
      setFormError(err instanceof Error ? err : new Error('Failed to start execution'))
    } finally {
      setFormLoading(false)
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-gray-900">Executions</h1>
        <button
          onClick={() => (showForm ? setShowForm(false) : openForm())}
          className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
        >
          {showForm ? 'Cancel' : 'Start Execution'}
        </button>
      </div>

      {error && <ErrorMessage error={error} />}

      {showForm && (
        <div className="rounded-xl border border-blue-200 bg-blue-50 p-6">
          <h2 className="mb-4 text-base font-semibold text-gray-800">Start Execution</h2>
          <form onSubmit={handleStart} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700">Workflow Definition</label>
              <select
                value={selectedDefId}
                onChange={(e) => setSelectedDefId(e.target.value)}
                required
                className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              >
                {definitions.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name} v{d.version}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700">Input (JSON)</label>
              <textarea
                value={inputJson}
                onChange={(e) => setInputJson(e.target.value)}
                rows={6}
                className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            {formError && <ErrorMessage error={formError} />}
            <button
              type="submit"
              disabled={formLoading}
              className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {formLoading ? 'Starting…' : 'Start'}
            </button>
          </form>
        </div>
      )}

      <div className="flex gap-2">
        {STATUS_FILTERS.map((s) => (
          <button
            key={s}
            onClick={() => setStatusFilter(s)}
            className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
              statusFilter === s
                ? 'bg-blue-600 text-white'
                : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
            }`}
          >
            {s}
          </button>
        ))}
      </div>

      {loading ? (
        <LoadingSpinner />
      ) : (
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-3">ID</th>
                <th className="px-4 py-3">Workflow</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Started</th>
                <th className="px-4 py-3">Duration</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {executions.map((exec) => (
                <tr
                  key={exec.id}
                  className="cursor-pointer hover:bg-gray-50"
                  onClick={() => navigate(`/executions/${exec.id}`)}
                >
                  <td className="px-4 py-3 font-mono text-xs text-blue-600">
                    {exec.id.slice(0, 8)}…
                  </td>
                  <td className="px-4 py-3 text-gray-900">{exec.workflow_name}</td>
                  <td className="px-4 py-3">
                    <Badge label={exec.status} variant={statusVariant(exec.status)} />
                  </td>
                  <td className="px-4 py-3 text-gray-500">
                    {new Date(exec.started_at).toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-gray-500">{durationLabel(exec)}</td>
                </tr>
              ))}
              {executions.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-gray-400">
                    No executions found.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
```

---

## Task 6: Execution Detail + Timeline Visualization

**File:** `admin-console/src/pages/ExecutionDetailPage.tsx`

**What it does:**
- Reads `:id` from URL params
- Fetches `GET /api/v1/workflow-executions/:id` and `GET /api/v1/workflow-executions/:id/timeline` concurrently
- Renders execution metadata (id, status badge, workflow name, started_at, finished_at, input/output JSON)
- Renders a vertical timeline where each `TimelineEvent` is a row with:
  - Colored left-border / dot indicating step status
  - Step name, step key (mono), status badge, attempt count
  - Duration (if available)
  - Error code + message (if present, rendered in red)
  - Compensation steps are indented and labeled "(compensation)"
- "Cancel" button: visible only when status is `RUNNING` or `WAITING`. Calls `POST /api/v1/workflow-executions/:id/cancel`
- "Send Signal" button: visible only when status is `WAITING`. Opens a small inline form with a JSON payload textarea. Calls `POST /api/v1/workflow-executions/:id/signal`

```typescript
import { useEffect, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { get, postEmpty, post } from '../api'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { ErrorMessage } from '../components/ErrorMessage'
import { Badge } from '../components/Badge'
import type {
  WorkflowExecution,
  ExecutionTimeline,
  TimelineEvent,
  ExecutionStatus,
  StepStatus,
} from '../types'

function executionStatusVariant(status: ExecutionStatus) {
  switch (status) {
    case 'COMPLETED':
    case 'COMPENSATED':
      return 'green'
    case 'RUNNING':
    case 'WAITING':
    case 'COMPENSATING':
      return 'blue'
    case 'FAILED':
    case 'CANCELED':
      return 'red'
    default:
      return 'gray'
  }
}

function stepStatusDotClass(status: StepStatus): string {
  switch (status) {
    case 'SUCCEEDED': return 'bg-green-500'
    case 'FAILED':
    case 'DEAD_LETTERED': return 'bg-red-500'
    case 'RUNNING': return 'bg-blue-500 animate-pulse'
    case 'RETRY_SCHEDULED': return 'bg-yellow-500'
    case 'SKIPPED': return 'bg-gray-300'
    default: return 'bg-gray-400'
  }
}

function stepStatusVariant(status: StepStatus) {
  switch (status) {
    case 'SUCCEEDED': return 'green'
    case 'FAILED':
    case 'DEAD_LETTERED': return 'red'
    case 'RUNNING': return 'blue'
    case 'RETRY_SCHEDULED': return 'yellow'
    default: return 'gray'
  }
}

function durationLabel(ms?: number): string {
  if (ms === undefined) return ''
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

interface SignalFormState {
  open: boolean
  payload: string
  loading: boolean
  error: Error | null
}

export function ExecutionDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { token } = useAuth()
  const navigate = useNavigate()
  const [execution, setExecution] = useState<WorkflowExecution | null>(null)
  const [timeline, setTimeline] = useState<ExecutionTimeline | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [actionError, setActionError] = useState<Error | null>(null)
  const [signalForm, setSignalForm] = useState<SignalFormState>({
    open: false,
    payload: '{}',
    loading: false,
    error: null,
  })

  useEffect(() => {
    if (!id) return
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const [exec, tl] = await Promise.all([
          get<WorkflowExecution>(`/api/v1/workflow-executions/${id}`, token ?? undefined),
          get<ExecutionTimeline>(`/api/v1/workflow-executions/${id}/timeline`, token ?? undefined),
        ])
        setExecution(exec)
        setTimeline(tl)
      } catch (err) {
        setError(err instanceof Error ? err : new Error('Failed to load execution'))
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [id, token])

  async function handleCancel() {
    if (!id) return
    setActionError(null)
    try {
      await postEmpty(`/api/v1/workflow-executions/${id}/cancel`, token ?? undefined)
      navigate(0) // reload
    } catch (err) {
      setActionError(err instanceof Error ? err : new Error('Cancel failed'))
    }
  }

  async function handleSignal(e: React.FormEvent) {
    e.preventDefault()
    if (!id) return
    setSignalForm((s) => ({ ...s, loading: true, error: null }))
    try {
      let payload: Record<string, unknown>
      try {
        payload = JSON.parse(signalForm.payload) as Record<string, unknown>
      } catch {
        throw new Error('Signal payload JSON is invalid')
      }
      await post(`/api/v1/workflow-executions/${id}/signal`, payload, token ?? undefined)
      setSignalForm({ open: false, payload: '{}', loading: false, error: null })
      navigate(0)
    } catch (err) {
      setSignalForm((s) => ({
        ...s,
        loading: false,
        error: err instanceof Error ? err : new Error('Signal failed'),
      }))
    }
  }

  if (loading) return <LoadingSpinner />
  if (error) return <ErrorMessage error={error} />
  if (!execution) return null

  const canCancel = execution.status === 'RUNNING' || execution.status === 'WAITING'
  const canSignal = execution.status === 'WAITING'

  return (
    <div className="space-y-8">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">Execution Detail</h1>
          <p className="mt-1 font-mono text-xs text-gray-500">{execution.id}</p>
        </div>
        <div className="flex gap-2">
          {canCancel && (
            <button
              onClick={handleCancel}
              className="rounded-md border border-red-300 bg-red-50 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-100 transition-colors"
            >
              Cancel
            </button>
          )}
          {canSignal && (
            <button
              onClick={() => setSignalForm((s) => ({ ...s, open: !s.open }))}
              className="rounded-md border border-yellow-300 bg-yellow-50 px-4 py-2 text-sm font-medium text-yellow-700 hover:bg-yellow-100 transition-colors"
            >
              Send Signal
            </button>
          )}
        </div>
      </div>

      {actionError && <ErrorMessage error={actionError} />}

      {signalForm.open && (
        <div className="rounded-xl border border-yellow-200 bg-yellow-50 p-6">
          <h2 className="mb-3 text-base font-semibold text-gray-800">Send Signal</h2>
          <form onSubmit={handleSignal} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700">
                Signal Payload (JSON)
              </label>
              <textarea
                value={signalForm.payload}
                onChange={(e) =>
                  setSignalForm((s) => ({ ...s, payload: e.target.value }))
                }
                rows={4}
                className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 font-mono text-xs focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
            {signalForm.error && <ErrorMessage error={signalForm.error} />}
            <button
              type="submit"
              disabled={signalForm.loading}
              className="rounded-md bg-yellow-600 px-4 py-2 text-sm font-medium text-white hover:bg-yellow-700 disabled:opacity-50 transition-colors"
            >
              {signalForm.loading ? 'Sending…' : 'Send'}
            </button>
          </form>
        </div>
      )}

      {/* Metadata */}
      <div className="grid grid-cols-2 gap-6">
        <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 text-base font-semibold text-gray-800">Details</h2>
          <dl className="space-y-3 text-sm">
            <div className="flex justify-between">
              <dt className="text-gray-500">Workflow</dt>
              <dd className="font-medium text-gray-900">{execution.workflow_name}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-gray-500">Status</dt>
              <dd>
                <Badge
                  label={execution.status}
                  variant={executionStatusVariant(execution.status)}
                />
              </dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-gray-500">Started</dt>
              <dd className="text-gray-900">
                {new Date(execution.started_at).toLocaleString()}
              </dd>
            </div>
            {execution.finished_at && (
              <div className="flex justify-between">
                <dt className="text-gray-500">Finished</dt>
                <dd className="text-gray-900">
                  {new Date(execution.finished_at).toLocaleString()}
                </dd>
              </div>
            )}
          </dl>
        </div>
        <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="mb-4 text-base font-semibold text-gray-800">Input</h2>
          <pre className="overflow-x-auto rounded border border-gray-100 bg-gray-50 p-3 text-xs text-gray-700">
            {JSON.stringify(execution.input, null, 2)}
          </pre>
          {execution.output && (
            <>
              <h2 className="mb-2 mt-4 text-base font-semibold text-gray-800">Output</h2>
              <pre className="overflow-x-auto rounded border border-gray-100 bg-gray-50 p-3 text-xs text-gray-700">
                {JSON.stringify(execution.output, null, 2)}
              </pre>
            </>
          )}
        </div>
      </div>

      {/* Timeline */}
      {timeline && (
        <div className="rounded-xl border border-gray-200 bg-white p-6 shadow-sm">
          <h2 className="mb-6 text-base font-semibold text-gray-800">Step Timeline</h2>
          <ol className="relative space-y-6 border-l-2 border-gray-200 pl-6">
            {timeline.events.map((event: TimelineEvent, idx: number) => (
              <li key={idx} className={event.is_compensation ? 'ml-6' : ''}>
                <div className={`absolute -left-1.5 mt-1.5 h-3 w-3 rounded-full border-2 border-white ${stepStatusDotClass(event.status)}`} />
                <div className="space-y-1">
                  <div className="flex items-center gap-3">
                    <span className="font-medium text-gray-900">{event.step_name}</span>
                    <span className="font-mono text-xs text-gray-400">{event.step_key}</span>
                    <Badge label={event.status} variant={stepStatusVariant(event.status)} />
                    {event.is_compensation && (
                      <span className="text-xs text-purple-600 font-medium">(compensation)</span>
                    )}
                  </div>
                  <div className="flex gap-4 text-xs text-gray-500">
                    <span>Attempt {event.attempt_count}</span>
                    {event.duration_ms !== undefined && (
                      <span>{durationLabel(event.duration_ms)}</span>
                    )}
                  </div>
                  {event.error_code && (
                    <div className="rounded border border-red-100 bg-red-50 px-3 py-2 text-xs text-red-700">
                      <span className="font-mono font-medium">{event.error_code}</span>
                      {event.error_message && `: ${event.error_message}`}
                    </div>
                  )}
                </div>
              </li>
            ))}
          </ol>
        </div>
      )}
    </div>
  )
}
```

---

## Task 7: Dead-Letter Management

**File:** `admin-console/src/pages/DeadLetterPage.tsx`

**What it does:**
- Fetches `GET /api/v1/dead-letter` and renders a table: step key, execution id (truncated, links to execution detail), attempt count, error code, created_at
- Clicking a row expands an inline detail panel showing:
  - Full error message
  - Step payload JSON
- "Replay" button per row: calls `POST /api/v1/dead-letter/{id}/replay`
  - On success: shows a temporary green "Replayed" toast (implemented as a timed state variable)
  - On failure: shows `ErrorMessage`

```typescript
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { get, postEmpty } from '../api'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { ErrorMessage } from '../components/ErrorMessage'
import type { DeadLetterItem, DeadLetterListResponse } from '../types'

export function DeadLetterPage() {
  const { token } = useAuth()
  const [items, setItems] = useState<DeadLetterItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [replayingId, setReplayingId] = useState<string | null>(null)
  const [replayedIds, setReplayedIds] = useState<Set<string>>(new Set())
  const [replayError, setReplayError] = useState<Error | null>(null)

  useEffect(() => {
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const res = await get<DeadLetterListResponse>('/api/v1/dead-letter', token ?? undefined)
        setItems(res.items)
      } catch (err) {
        setError(err instanceof Error ? err : new Error('Failed to load dead-letter items'))
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [token])

  async function handleReplay(id: string) {
    setReplayingId(id)
    setReplayError(null)
    try {
      await postEmpty(`/api/v1/dead-letter/${id}/replay`, token ?? undefined)
      setReplayedIds((prev) => new Set([...prev, id]))
      setTimeout(() => {
        setReplayedIds((prev) => {
          const next = new Set(prev)
          next.delete(id)
          return next
        })
      }, 3000)
    } catch (err) {
      setReplayError(err instanceof Error ? err : new Error('Replay failed'))
    } finally {
      setReplayingId(null)
    }
  }

  if (loading) return <LoadingSpinner />

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-gray-900">Dead Letter Queue</h1>
      {error && <ErrorMessage error={error} />}
      {replayError && <ErrorMessage error={replayError} />}

      <div className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
            <tr>
              <th className="px-4 py-3">Step</th>
              <th className="px-4 py-3">Execution</th>
              <th className="px-4 py-3">Attempts</th>
              <th className="px-4 py-3">Error</th>
              <th className="px-4 py-3">Created</th>
              <th className="px-4 py-3">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {items.map((item) => (
              <>
                <tr
                  key={item.id}
                  className="cursor-pointer hover:bg-gray-50"
                  onClick={() =>
                    setExpandedId(expandedId === item.id ? null : item.id)
                  }
                >
                  <td className="px-4 py-3">
                    <div className="font-medium text-gray-900">{item.step_name}</div>
                    <div className="font-mono text-xs text-gray-400">{item.step_key}</div>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">
                    <Link
                      to={`/executions/${item.execution_id}`}
                      onClick={(e) => e.stopPropagation()}
                      className="text-blue-600 hover:underline"
                    >
                      {item.execution_id.slice(0, 8)}…
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{item.attempt_count}</td>
                  <td className="px-4 py-3 font-mono text-xs text-red-600">
                    {item.error_code ?? '—'}
                  </td>
                  <td className="px-4 py-3 text-gray-500">
                    {new Date(item.created_at).toLocaleString()}
                  </td>
                  <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                    {replayedIds.has(item.id) ? (
                      <span className="text-xs font-medium text-green-600">Replayed ✓</span>
                    ) : (
                      <button
                        onClick={() => handleReplay(item.id)}
                        disabled={replayingId === item.id}
                        className="rounded bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800 hover:bg-blue-200 disabled:opacity-50 transition-colors"
                      >
                        {replayingId === item.id ? 'Replaying…' : 'Replay'}
                      </button>
                    )}
                  </td>
                </tr>
                {expandedId === item.id && (
                  <tr key={`${item.id}-detail`}>
                    <td colSpan={6} className="bg-gray-50 px-4 py-4">
                      {item.error_message && (
                        <p className="mb-3 text-sm text-red-700">{item.error_message}</p>
                      )}
                      <p className="mb-1 text-xs font-medium text-gray-500">Payload</p>
                      <pre className="overflow-x-auto rounded border border-gray-200 bg-white p-4 text-xs text-gray-700">
                        {JSON.stringify(item.payload, null, 2)}
                      </pre>
                    </td>
                  </tr>
                )}
              </>
            ))}
            {items.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-8 text-center text-gray-400">
                  No dead-letter items.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
```

---

## Task 8: Audit Trail Page

**File:** `admin-console/src/pages/AuditTrailPage.tsx`

**What it does:**
- Fetches `GET /api/v1/audit-events` with optional query params: `event_type`, `resource_type`, `from` (ISO date), `to` (ISO date)
- Renders a filter bar above the table with text inputs for event_type filter and resource_type filter, plus date-range pickers (native `<input type="date">`)
- Applies filters on form submit (not on every keystroke)
- Table columns: timestamp, event_type, actor (email or id), resource_type + resource_id, payload preview (first 80 chars of JSON string)
- Clicking a row expands full payload JSON inline below that row

```typescript
import { useEffect, useState, type FormEvent } from 'react'
import { useAuth } from '../contexts/AuthContext'
import { get } from '../api'
import { LoadingSpinner } from '../components/LoadingSpinner'
import { ErrorMessage } from '../components/ErrorMessage'
import type { AuditEvent, AuditEventListResponse } from '../types'

interface Filters {
  event_type: string
  resource_type: string
  from: string
  to: string
}

function buildQuery(filters: Filters): string {
  const params = new URLSearchParams()
  if (filters.event_type) params.set('event_type', filters.event_type)
  if (filters.resource_type) params.set('resource_type', filters.resource_type)
  if (filters.from) params.set('from', new Date(filters.from).toISOString())
  if (filters.to) params.set('to', new Date(filters.to).toISOString())
  const q = params.toString()
  return q ? `?${q}` : ''
}

function payloadPreview(payload: Record<string, unknown>): string {
  const s = JSON.stringify(payload)
  return s.length > 80 ? s.slice(0, 80) + '…' : s
}

export function AuditTrailPage() {
  const { token } = useAuth()
  const [events, setEvents] = useState<AuditEvent[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<Error | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [filters, setFilters] = useState<Filters>({
    event_type: '',
    resource_type: '',
    from: '',
    to: '',
  })
  const [pendingFilters, setPendingFilters] = useState<Filters>(filters)

  useEffect(() => {
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const res = await get<AuditEventListResponse>(
          `/api/v1/audit-events${buildQuery(filters)}`,
          token ?? undefined,
        )
        setEvents(res.items)
      } catch (err) {
        setError(err instanceof Error ? err : new Error('Failed to load audit events'))
      } finally {
        setLoading(false)
      }
    }
    load()
  }, [filters, token])

  function handleFilterSubmit(e: FormEvent) {
    e.preventDefault()
    setFilters({ ...pendingFilters })
  }

  function handleReset() {
    const empty: Filters = { event_type: '', resource_type: '', from: '', to: '' }
    setPendingFilters(empty)
    setFilters(empty)
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-gray-900">Audit Trail</h1>

      <form
        onSubmit={handleFilterSubmit}
        className="flex flex-wrap items-end gap-4 rounded-xl border border-gray-200 bg-white p-4 shadow-sm"
      >
        <div>
          <label className="block text-xs font-medium text-gray-600">Event Type</label>
          <input
            type="text"
            value={pendingFilters.event_type}
            onChange={(e) =>
              setPendingFilters((f) => ({ ...f, event_type: e.target.value }))
            }
            placeholder="EXECUTION_STARTED"
            className="mt-1 rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-600">Resource Type</label>
          <input
            type="text"
            value={pendingFilters.resource_type}
            onChange={(e) =>
              setPendingFilters((f) => ({ ...f, resource_type: e.target.value }))
            }
            placeholder="WORKFLOW_EXECUTION"
            className="mt-1 rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-600">From</label>
          <input
            type="date"
            value={pendingFilters.from}
            onChange={(e) =>
              setPendingFilters((f) => ({ ...f, from: e.target.value }))
            }
            className="mt-1 rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-gray-600">To</label>
          <input
            type="date"
            value={pendingFilters.to}
            onChange={(e) =>
              setPendingFilters((f) => ({ ...f, to: e.target.value }))
            }
            className="mt-1 rounded-md border border-gray-300 px-3 py-1.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <div className="flex gap-2">
          <button
            type="submit"
            className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 transition-colors"
          >
            Apply
          </button>
          <button
            type="button"
            onClick={handleReset}
            className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors"
          >
            Reset
          </button>
        </div>
      </form>

      {error && <ErrorMessage error={error} />}

      {loading ? (
        <LoadingSpinner />
      ) : (
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-left text-xs font-medium uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-4 py-3">Timestamp</th>
                <th className="px-4 py-3">Event Type</th>
                <th className="px-4 py-3">Actor</th>
                <th className="px-4 py-3">Resource</th>
                <th className="px-4 py-3">Payload Preview</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {events.map((event) => (
                <>
                  <tr
                    key={event.id}
                    className="cursor-pointer hover:bg-gray-50"
                    onClick={() =>
                      setExpandedId(expandedId === event.id ? null : event.id)
                    }
                  >
                    <td className="px-4 py-3 text-xs text-gray-500 whitespace-nowrap">
                      {new Date(event.created_at).toLocaleString()}
                    </td>
                    <td className="px-4 py-3 font-mono text-xs font-medium text-gray-800">
                      {event.event_type}
                    </td>
                    <td className="px-4 py-3 text-xs text-gray-700">
                      {event.actor_email ?? event.actor_id}
                    </td>
                    <td className="px-4 py-3 text-xs">
                      <span className="font-medium text-gray-700">{event.resource_type}</span>
                      <span className="ml-1 font-mono text-gray-400">
                        {event.resource_id.slice(0, 8)}…
                      </span>
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-gray-400">
                      {payloadPreview(event.payload)}
                    </td>
                  </tr>
                  {expandedId === event.id && (
                    <tr key={`${event.id}-detail`}>
                      <td colSpan={5} className="bg-gray-50 px-4 py-4">
                        <pre className="overflow-x-auto rounded border border-gray-200 bg-white p-4 text-xs text-gray-700">
                          {JSON.stringify(event.payload, null, 2)}
                        </pre>
                      </td>
                    </tr>
                  )}
                </>
              ))}
              {events.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-8 text-center text-gray-400">
                    No audit events found.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
```

---

## Docker Integration

### `admin-console/nginx.conf`

```nginx
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    # Serve static assets with caching
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # SPA fallback — all non-asset routes serve index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Proxy API calls to backend services
    location /api/v1/auth/ {
        proxy_pass http://identity-service:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /api/v1/tenants/ {
        proxy_pass http://identity-service:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /api/v1/users/ {
        proxy_pass http://identity-service:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /api/v1/workflow-definitions/ {
        proxy_pass http://workflow-service:8082;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /api/v1/workflow-executions/ {
        proxy_pass http://workflow-service:8082;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /api/v1/dead-letter/ {
        proxy_pass http://workflow-service:8082;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /api/v1/audit-events/ {
        proxy_pass http://audit-service:8084;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

### `admin-console/Dockerfile`

```dockerfile
# Build stage
FROM node:22-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Production stage
FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

### docker-compose.yml addition

Add to the existing `docker-compose.yml` at the project root:

```yaml
  admin-console:
    build:
      context: ./admin-console
      dockerfile: Dockerfile
    ports:
      - "3001:80"
    depends_on:
      identity-service:
        condition: service_healthy
      workflow-service:
        condition: service_healthy
      audit-service:
        condition: service_healthy
    networks:
      - atlas-network
```

---

## Acceptance Criteria

- `cd admin-console && npm run dev` starts the dev server at http://localhost:5173 with no TypeScript errors
- `npm run build` produces a clean production build with no TypeScript errors
- `docker compose up admin-console` serves the UI at http://localhost:3001
- All pages load without console errors against a running Atlas backend
- Login with `admin@acme.com` / `Atlas2026!` succeeds and redirects to dashboard
- All 8 tasks are implemented; all data fetches show loading spinners and handle errors
- No `any` types in TypeScript source; `tsc --noEmit` passes clean
- Tailwind is used for all styling; no separate CSS files
