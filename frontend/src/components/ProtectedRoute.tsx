import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

interface Props {
  /** Restrict to a single role. Omit to allow any authenticated user. */
  requireRole?: 'ADMIN';
}

/**
 * Gate for routes that need a session.
 *
 * This is a usability measure, not a security control. The server authorises every
 * request independently, so bypassing this component in the browser reveals an empty page
 * and a 401 from the API, never someone else's data. Hiding a route is never what keeps it
 * safe.
 */
export function ProtectedRoute({ requireRole }: Props) {
  const { status, isAdmin } = useAuth();
  const location = useLocation();

  // Still asking the server who this is. Rendering a redirect now would bounce a
  // signed-in user to the login page on every refresh.
  if (status === 'unknown') {
    return (
      <div className="page">
        <p className="status status--pending" role="status">
          Checking your session&hellip;
        </p>
      </div>
    );
  }

  if (status === 'anonymous') {
    // Remember where they were headed so login can return them there.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  if (requireRole === 'ADMIN' && !isAdmin) {
    return (
      <div className="page">
        <section className="panel empty-state">
          <h1>Not available</h1>
          <p>Your account does not have access to this area.</p>
        </section>
      </div>
    );
  }

  return <Outlet />;
}
