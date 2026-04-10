import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './contexts/AuthContext'
import { Layout } from './components/Layout'
import { LoginPage } from './pages/LoginPage'
import { DashboardPage } from './pages/DashboardPage'
import { WorkflowDefinitionsPage } from './pages/WorkflowDefinitionsPage'
import { WorkflowExecutionsPage } from './pages/WorkflowExecutionsPage'
import { ExecutionDetailPage } from './pages/ExecutionDetailPage'
import { DeadLetterPage } from './pages/DeadLetterPage'
import { AuditTrailPage } from './pages/AuditTrailPage'

function PrivateRoutes() {
  const { isAuthenticated } = useAuth()
  if (!isAuthenticated) return <Navigate to="/login" replace />

  return (
    <Layout>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/definitions" element={<WorkflowDefinitionsPage />} />
        <Route path="/executions" element={<WorkflowExecutionsPage />} />
        <Route path="/executions/:id" element={<ExecutionDetailPage />} />
        <Route path="/dead-letter" element={<DeadLetterPage />} />
        <Route path="/audit" element={<AuditTrailPage />} />
      </Routes>
    </Layout>
  )
}

export function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/*" element={<PrivateRoutes />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
