import { createContext, useContext, useState, useCallback, useEffect, useRef, type ReactNode } from 'react'
import { post } from '../api'
import type { LoginRequest, LoginResponse, RefreshTokenRequest } from '../types'

const SESSION_ACCESS_TOKEN_KEY = 'atlas_access_token'
const SESSION_REFRESH_TOKEN_KEY = 'atlas_refresh_token'

/** Refresh 3 minutes before the 15-minute access token expires. */
const REFRESH_INTERVAL_MS = 12 * 60 * 1000

interface AuthState {
  token: string | null
  isAuthenticated: boolean
}

interface AuthContextValue extends AuthState {
  login: (tenantSlug: string, email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(
    () => sessionStorage.getItem(SESSION_ACCESS_TOKEN_KEY),
  )

  const refreshTokenRef = useRef<string | null>(
    sessionStorage.getItem(SESSION_REFRESH_TOKEN_KEY),
  )
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null)

  /** Persist both tokens in sessionStorage and update state. */
  const storeTokens = useCallback((accessToken: string, refreshToken: string) => {
    sessionStorage.setItem(SESSION_ACCESS_TOKEN_KEY, accessToken)
    sessionStorage.setItem(SESSION_REFRESH_TOKEN_KEY, refreshToken)
    refreshTokenRef.current = refreshToken
    setToken(accessToken)
  }, [])

  /** Remove all stored tokens and clear the refresh interval. */
  const clearSession = useCallback(() => {
    sessionStorage.removeItem(SESSION_ACCESS_TOKEN_KEY)
    sessionStorage.removeItem(SESSION_REFRESH_TOKEN_KEY)
    refreshTokenRef.current = null
    setToken(null)
    if (intervalRef.current) {
      clearInterval(intervalRef.current)
      intervalRef.current = null
    }
  }, [])

  /** Call the refresh endpoint and rotate both tokens. */
  const refreshAccessToken = useCallback(async () => {
    const currentRefreshToken = refreshTokenRef.current
    if (!currentRefreshToken) {
      clearSession()
      return
    }
    try {
      const response = await post<LoginResponse>('/api/v1/auth/refresh', {
        refreshToken: currentRefreshToken,
      } satisfies RefreshTokenRequest)
      storeTokens(response.accessToken, response.refreshToken)
    } catch {
      // Refresh failed -- session is no longer valid.
      clearSession()
    }
  }, [clearSession, storeTokens])

  /** Start the background refresh interval. */
  const startRefreshInterval = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current)
    }
    intervalRef.current = setInterval(() => {
      void refreshAccessToken()
    }, REFRESH_INTERVAL_MS)
  }, [refreshAccessToken])

  const login = useCallback(async (tenantSlug: string, email: string, password: string) => {
    const response = await post<LoginResponse>('/api/v1/auth/login', {
      tenantSlug,
      email,
      password,
    } satisfies LoginRequest)
    storeTokens(response.accessToken, response.refreshToken)
    startRefreshInterval()
  }, [storeTokens, startRefreshInterval])

  const logout = useCallback(() => {
    clearSession()
  }, [clearSession])

  // On mount, if we already have a refresh token (page reload), kick off the
  // refresh interval so the session stays alive.
  useEffect(() => {
    if (refreshTokenRef.current) {
      startRefreshInterval()
    }
    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current)
      }
    }
  }, [startRefreshInterval])

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
