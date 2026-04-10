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
    case 'PUBLISHED': return 'green' as const
    case 'DRAFT': return 'yellow' as const
    case 'DEPRECATED': return 'gray' as const
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
              {formLoading ? 'Creating\u2026' : 'Create Definition'}
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
              <tbody key={def.id}>
                <tr className="hover:bg-gray-50">
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
              </tbody>
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
