import { useState } from 'react';
import type { FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { FormField } from '../components/FormField';
import { apiFieldErrors, describeApiError } from '../services/apiClient';

export function RegisterPage() {
  const { status, register } = useAuth();
  const navigate = useNavigate();

  const [username, setUsername] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  if (status === 'authenticated') {
    return <Navigate to="/profile" replace />;
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      await register({ username, email, password });
      // The server does not sign the user in on registration, so send them to the login
      // form rather than pretending a session exists.
      navigate('/login', {
        replace: true,
        state: { registered: true },
      });
    } catch (caught) {
      // Server-side validation is authoritative; surface its per-field messages next to
      // the inputs they belong to rather than collapsing them into one banner.
      const violations = apiFieldErrors(caught);
      if (violations.length > 0) {
        setFieldErrors(Object.fromEntries(violations.map((v) => [v.field, v.message])));
      } else {
        setError(describeApiError(caught));
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="page page--narrow">
      <section className="panel">
        <h1>Create an account</h1>

        <form onSubmit={handleSubmit} noValidate>
          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}

          <FormField
            id="username"
            label="Username"
            error={fieldErrors.username}
            hint="3–32 characters: letters, digits, underscores or hyphens."
          >
            <input
              id="username"
              name="username"
              type="text"
              autoComplete="username"
              autoFocus
              required
              aria-describedby={fieldErrors.username ? 'username-error' : 'username-hint'}
              aria-invalid={Boolean(fieldErrors.username)}
              value={username}
              onChange={(event) => setUsername(event.target.value)}
            />
          </FormField>

          <FormField id="email" label="Email" error={fieldErrors.email}>
            <input
              id="email"
              name="email"
              type="email"
              autoComplete="email"
              required
              aria-describedby={fieldErrors.email ? 'email-error' : undefined}
              aria-invalid={Boolean(fieldErrors.email)}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </FormField>

          <FormField
            id="password"
            label="Password"
            error={fieldErrors.password}
            hint="At least 10 characters. A memorable phrase beats a short, complicated one."
          >
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="new-password"
              required
              aria-describedby={fieldErrors.password ? 'password-error' : 'password-hint'}
              aria-invalid={Boolean(fieldErrors.password)}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </FormField>

          <button className="button" type="submit" disabled={submitting}>
            {submitting ? 'Creating account…' : 'Create account'}
          </button>
        </form>

        <p className="form-aside">
          Already registered? <Link to="/login">Sign in</Link>.
        </p>
      </section>
    </div>
  );
}
