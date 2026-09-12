import { createContext } from 'react';
import type { LoginPayload, RegistrationPayload, UserProfile } from '../types/auth';

/**
 * Where the app is in resolving who the user is.
 *
 * `unknown` matters as much as the other two: on first paint the browser may already hold
 * a valid session cookie, and rendering a signed-out view before `/api/auth/me` answers
 * would flash the login screen at someone who is already signed in — or worse, bounce
 * them off a protected page.
 */
export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous';

export interface AuthContextValue {
  status: AuthStatus;
  user: UserProfile | null;
  isAdmin: boolean;
  login: (payload: LoginPayload) => Promise<UserProfile>;
  register: (payload: RegistrationPayload) => Promise<UserProfile>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
}

/**
 * Kept in its own module, separate from the provider component, so the file exports only
 * non-components. Mixing a context object and a component in one file breaks React Fast
 * Refresh during development.
 */
export const AuthContext = createContext<AuthContextValue | undefined>(undefined);
