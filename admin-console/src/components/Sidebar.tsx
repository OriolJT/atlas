import { NavLink } from 'react-router-dom'
import { useAuth } from '../contexts/AuthContext'

const navItems = [
  { to: '/', label: 'Dashboard', exact: true },
  { to: '/definitions', label: 'Workflow Definitions', exact: false },
  { to: '/executions', label: 'Executions', exact: false },
  { to: '/dead-letter', label: 'Dead Letter', exact: false },
  { to: '/audit', label: 'Audit Trail', exact: false },
]

export function Sidebar() {
  const { logout } = useAuth()

  return (
    <aside className="flex h-screen w-56 flex-col bg-gray-900 text-white">
      <div className="px-6 py-5 text-lg font-semibold tracking-tight text-white">
        Atlas
      </div>
      <nav className="flex-1 space-y-1 px-3">
        {navItems.map(({ to, label, exact }) => (
          <NavLink
            key={to}
            to={to}
            end={exact}
            className={({ isActive }) =>
              `block rounded-md px-3 py-2 text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-gray-700 text-white'
                  : 'text-gray-400 hover:bg-gray-800 hover:text-white'
              }`
            }
          >
            {label}
          </NavLink>
        ))}
      </nav>
      <div className="px-3 py-4">
        <button
          onClick={logout}
          className="w-full rounded-md px-3 py-2 text-left text-sm font-medium text-gray-400 hover:bg-gray-800 hover:text-white transition-colors"
        >
          Sign out
        </button>
      </div>
    </aside>
  )
}
