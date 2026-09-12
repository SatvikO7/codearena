import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

/**
 * Primary navigation. Entries are added as their routes become real; a link is never
 * shown for a page that does not exist yet.
 */
const NAV_ITEMS: { to: string; label: string; end?: boolean }[] = [
  { to: '/', label: 'Home', end: true },
  { to: '/problems', label: 'Problems' },
  { to: '/submissions', label: 'My submissions' },
];

/** Shown only to administrators, and only as a shortcut: the server authorises regardless. */
const ADMIN_NAV_ITEMS: { to: string; label: string }[] = [
  { to: '/admin/problems', label: 'Manage problems' },
];

/** Shared chrome: masthead, primary navigation, account controls and the routed page. */
export function AppLayout() {
  const { status, user, isAdmin, logout } = useAuth();
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate('/', { replace: true });
  }

  return (
    <div className="app-shell">
      <header className="app-header">
        <NavLink to="/" className="brand" end>
          Code<span>Arena</span>
        </NavLink>

        <nav aria-label="Primary">
          <ul className="nav-list">
            {NAV_ITEMS.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) => (isActive ? 'nav-link is-active' : 'nav-link')}
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
            {isAdmin &&
              ADMIN_NAV_ITEMS.map((item) => (
                <li key={item.to}>
                  <NavLink
                    to={item.to}
                    className={({ isActive }) => (isActive ? 'nav-link is-active' : 'nav-link')}
                  >
                    {item.label}
                  </NavLink>
                </li>
              ))}
          </ul>
        </nav>

        <div className="account-controls">
          {/* Render nothing until the session is resolved, so the header does not flash
              "Sign in" at somebody who is already signed in. */}
          {status === 'authenticated' && user && (
            <>
              <NavLink to="/profile" className="account-name">
                {user.username}
              </NavLink>
              <button type="button" className="button button--quiet" onClick={handleLogout}>
                Sign out
              </button>
            </>
          )}
          {status === 'anonymous' && (
            <>
              <NavLink to="/login" className="nav-link">
                Sign in
              </NavLink>
              <NavLink to="/register" className="button button--quiet">
                Register
              </NavLink>
            </>
          )}
        </div>
      </header>

      <main className="app-main">
        <Outlet />
      </main>

      <footer className="app-footer">
        <span>CodeArena &mdash; an online judge with sandboxed, asynchronous code execution.</span>
      </footer>
    </div>
  );
}
