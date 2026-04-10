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
              {signalForm.loading ? 'Sending\u2026' : 'Send'}
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
