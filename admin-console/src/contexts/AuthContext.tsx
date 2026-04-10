import { createContext, useContext, useState, useCallback, type ReactNode } from 'react'
import { post } from '../api'
import type { LoginRequest, LoginResponse } from '../types'

interface AuthState {
  token: string | null
  isAuthenticated: boolean
}

interface AuthContextValue extends AuthState {
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(null)

  const login = useCallback(async (email: string, password: string) => {
    const response = await post<LoginResponse>('/api/v1/auth/login', {
      email,
      password,
    } satisfies LoginRequest)
    setToken(response.access_token)
  }, [])

  const logout = useCallback(() => {
    setToken(null)
  }, [])

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
