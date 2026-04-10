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
  return s.length > 80 ? s.slice(0, 80) + '\u2026' : s
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
                        {event.resource_id.slice(0, 8)}\u2026
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
