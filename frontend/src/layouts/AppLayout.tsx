import { NavLink, Outlet } from 'react-router-dom';

/**
 * Primary navigation. Entries are added as their routes become real; a link is never
 * shown for a page that does not exist yet.
 */
const NAV_ITEMS: { to: string; label: string; end?: boolean }[] = [
  { to: '/', label: 'Home', end: true },
];

/** Shared chrome: masthead, primary navigation and the routed page body. */
export function AppLayout() {
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
          </ul>
        </nav>
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
