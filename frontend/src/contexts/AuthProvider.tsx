import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { AuthContext } from './AuthContext';
import type { AuthContextValue, AuthStatus } from './AuthContext';
import * as authService from '../services/authService';
import type { LoginPayload, RegistrationPayload, UserProfile } from '../types/auth';

/**
 * Holds the authenticated user for the whole app.
 *
 * There is no token here, and nothing is written to localStorage. The session lives in an
 * HttpOnly cookie the browser attaches on its own, so this state is only a cache of what
 * the server already knows — it cannot be tampered with to gain access, because every
 * request is authorised server-side regardless of what this context says.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserProfile | null>(null);
  const [status, setStatus] = useState<AuthStatus>('unknown');

  const resolveSession = useCallback(async (signal?: AbortSignal) => {
    try {
      const currentUser = await authService.fetchCurrentUser(signal);
      setUser(currentUser);
      setStatus(currentUser ? 'authenticated' : 'anonymous');
    } catch {
      // The API is unreachable rather than the user being signed out. Treat it as
      // anonymous so the app still renders, instead of hanging on a spinner forever.
      setUser(null);
      setStatus('anonymous');
    }
  }, []);

  // Ask the server who the caller is, once, on mount. This is synchronisation with an
  // external system, which is exactly what an effect is for: the answer lives in an
  // HttpOnly cookie the client cannot read, so it can only be obtained by asking.
  useEffect(() => {
    const controller = new AbortController();
    // The linter cannot see that the state updates happen after the request resolves
    // rather than synchronously during the effect, so there is no cascading render to
    // avoid here. Aborting on unmount keeps a late response from touching dead state.
    // oxlint-disable-next-line set-state-in-effect
    void resolveSession(controller.signal);
    return () => controller.abort();
  }, [resolveSession]);

  const login = useCallback(async (payload: LoginPayload) => {
    const loggedIn = await authService.login(payload);
    setUser(loggedIn);
    setStatus('authenticated');
    return loggedIn;
  }, []);

  const register = useCallback(async (payload: RegistrationPayload) => {
    // Registration deliberately does not sign the user in; the server issues no session
    // here, so the client must not pretend one exists.
    return authService.register(payload);
  }, []);

  const logout = useCallback(async () => {
    try {
      await authService.logout();
    } finally {
      // Clear local state even if the call failed. The server-side session is the real
      // authority, and leaving a stale user on screen after a logout attempt is worse
      // than optimistically clearing it.
      setUser(null);
      setStatus('anonymous');
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      user,
      isAdmin: user?.role === 'ADMIN',
      login,
      register,
      logout,
      refresh: () => resolveSession(),
    }),
    [status, user, login, register, logout, resolveSession],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
