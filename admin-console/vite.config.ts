import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api/v1/auth': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/v1/tenants': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/v1/users': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/v1/workflow-definitions': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api/v1/workflow-executions': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api/v1/dead-letter': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/api/v1/audit-events': {
        target: 'http://localhost:8084',
        changeOrigin: true,
      },
    },
  },
})
