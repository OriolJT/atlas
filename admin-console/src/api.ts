import type { ApiError } from './types'

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
