import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'
import { ErrorMessage } from '../components/ErrorMessage'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [tenantSlug, setTenantSlug] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<Error | null>(null)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError(null)
    try {
      await login(tenantSlug, email, password)
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
              htmlFor="tenantSlug"
              className="block text-sm font-medium text-gray-700"
            >
              Tenant
            </label>
            <input
              id="tenantSlug"
              type="text"
              required
              autoComplete="organization"
              value={tenantSlug}
              onChange={(e) => setTenantSlug(e.target.value)}
              className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 text-sm shadow-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500"
              placeholder="e.g. acme"
            />
          </div>
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
            {loading ? 'Signing in\u2026' : 'Sign in'}
          </button>
        </form>
        <p className="mt-4 text-center text-xs text-gray-400">
          Demo credentials: admin@acme.com / Atlas2026!
        </p>
      </div>
    </div>
  )
}
