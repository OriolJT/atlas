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
      return 'green' as const
    case 'RUNNING':
    case 'WAITING':
    case 'COMPENSATING':
      return 'blue' as const
    case 'FAILED':
    case 'CANCELED':
      return 'red' as const
    default:
      return 'gray' as const
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
          active: activeRes.items.length > 0 ? 1 : 0,
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
                      {exec.id.slice(0, 8)}&hellip;
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
