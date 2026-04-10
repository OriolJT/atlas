import { Fragment, useEffect, useState } from 'react'
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
              <Fragment key={item.id}>
                <tr
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
                      {item.execution_id.slice(0, 8)}\u2026
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-gray-700">{item.attempt_count}</td>
                  <td className="px-4 py-3 font-mono text-xs text-red-600">
                    {item.error_code ?? '\u2014'}
                  </td>
                  <td className="px-4 py-3 text-gray-500">
                    {new Date(item.created_at).toLocaleString()}
                  </td>
                  <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                    {replayedIds.has(item.id) ? (
                      <span className="text-xs font-medium text-green-600">Replayed</span>
                    ) : (
                      <button
                        onClick={() => handleReplay(item.id)}
                        disabled={replayingId === item.id}
                        className="rounded bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800 hover:bg-blue-200 disabled:opacity-50 transition-colors"
                      >
                        {replayingId === item.id ? 'Replaying\u2026' : 'Replay'}
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
              </Fragment>
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
