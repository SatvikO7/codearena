import { useAuth } from '../hooks/useAuth';

/**
 * The signed-in user's own account.
 *
 * Reached only through {@link ProtectedRoute}; the data itself comes from
 * `GET /api/auth/me`, which the server scopes to the caller's own session.
 */
export function ProfilePage() {
  const { user } = useAuth();

  if (!user) {
    return null;
  }

  return (
    <div className="page">
      <section className="hero">
        <h1>{user.username}</h1>
        <p className="lede">Your account details.</p>
      </section>

      <section className="panel">
        <h2>Account</h2>
        <dl className="detail-grid">
          <div>
            <dt>Username</dt>
            <dd>{user.username}</dd>
          </div>
          <div>
            <dt>Email</dt>
            <dd>{user.email}</dd>
          </div>
          <div>
            <dt>Role</dt>
            <dd>{user.role}</dd>
          </div>
          <div>
            <dt>Status</dt>
            <dd>{user.enabled ? 'Active' : 'Disabled'}</dd>
          </div>
          <div>
            <dt>Member since</dt>
            <dd>{new Date(user.createdAt).toLocaleDateString()}</dd>
          </div>
          <div>
            <dt>Account ID</dt>
            <dd>{user.id}</dd>
          </div>
        </dl>
      </section>
    </div>
  );
}
