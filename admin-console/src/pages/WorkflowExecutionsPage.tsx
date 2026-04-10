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
  if (!exec.finished_at) return '\u2014'
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
              {formLoading ? 'Starting\u2026' : 'Start'}
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
                    {exec.id.slice(0, 8)}\u2026
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
