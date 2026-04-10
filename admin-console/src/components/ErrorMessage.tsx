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
