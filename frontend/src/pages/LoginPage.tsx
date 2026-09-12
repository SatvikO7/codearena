import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { FormField } from '../components/FormField';
import { describeApiError } from '../services/apiClient';

export function LoginPage() {
  const { status, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Where the user was going before being redirected here.
  const destination = (location.state as { from?: string } | null)?.from ?? '/profile';

  if (status === 'authenticated') {
    return <Navigate to={destination} replace />;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login({ identifier, password });
      navigate(destination, { replace: true });
    } catch (caught) {
      // The server answers a wrong password and an unknown account identically, so this
      // message is whatever it sent; the client must not try to be more specific.
      setError(describeApiError(caught));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="page page--narrow">
      <section className="panel">
        <h1>Sign in</h1>

        <form onSubmit={handleSubmit} noValidate>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}

          <FormField id="identifier" label="Username or email">
            <input
              id="identifier"
              name="identifier"
              type="text"
              autoComplete="username"
              autoFocus
              required
              value={identifier}
              onChange={(event) => setIdentifier(event.target.value)}
            />
          </FormField>

          <FormField id="password" label="Password">
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </FormField>

          <button className="button" type="submit" disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p className="form-aside">
          No account yet? <Link to="/register">Create one</Link>.
        </p>
      </section>
    </div>
  );
}
