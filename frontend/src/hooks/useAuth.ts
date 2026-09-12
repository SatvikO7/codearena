import { useContext } from 'react';
import { AuthContext } from '../contexts/AuthContext';
import type { AuthContextValue } from '../contexts/AuthContext';

/**
 * Access to the authenticated user.
 *
 * Throws rather than returning undefined when used outside the provider: that is a wiring
 * mistake, and failing loudly at the point of use beats every consumer having to
 * null-check something that should always be present.
 */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
